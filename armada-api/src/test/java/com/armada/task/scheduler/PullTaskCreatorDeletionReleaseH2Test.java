package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.impl.AccountCreatorDeletionServiceImpl;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskCreatorDeletionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskNormalLinkSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 真 Mapper、租户插件和 Spring 事务覆盖释放判据、审计、命令门禁与竞争。 */
class PullTaskCreatorDeletionReleaseH2Test {
    private AccountTakeoverH2Support h;
    private PullTaskCreatorDeletionMapper ledger;
    private PullTaskCreatorDeletionTransactionService service;
    private SqlSessionTemplate session;

    @BeforeEach
    void setup() throws Exception {
        h = new AccountTakeoverH2Support();
        for (String ddl : PullTaskNormalLinkSchema.all()) h.jdbc.execute(ddl);
        String migration = new ClassPathResource("db/migration/V211__new_group_creator_deletion.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = migration.indexOf("CREATE TABLE IF NOT EXISTS pull_task_creator_deletion");
        h.jdbc.execute(migration.substring(start, migration.indexOf(';', start)));
        try (var connection = h.dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V217__account_creator_deletion_release.sql"));
        }
        var config = new MyBatisConfig();
        session = new SqlSessionTemplate(PullTaskNormalLinkH2Support.sqlSessionFactory(h.dataSource,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/task/PullTaskCreatorDeletionMapper.xml", "mapper/task/PullTaskGroupAccountMapper.xml",
                "mapper/task/PullTaskGroupExecutionMapper.xml", "mapper/task/PullTaskMapper.xml"));
        ledger = session.getMapper(PullTaskCreatorDeletionMapper.class);
        var accountService = h.transactional(new AccountCreatorDeletionServiceImpl(
                new SqlSessionTemplate(h.sqlSessionFactory).getMapper(AccountCreatorDeletionMapper.class)),
                AccountCreatorDeletionService.class);
        service = h.transactional(new PullTaskCreatorDeletionTransactionService(ledger,
                mock(PullTaskCreatorDeletionGate.class), new PullTaskCreatorDeletionResources(
                        session.getMapper(PullTaskMapper.class), session.getMapper(PullTaskGroupExecutionMapper.class),
                        session.getMapper(PullTaskGroupAccountMapper.class), mock(PullTaskAccountActionMapper.class),
                        accountService, mock(AccountProtocolLookupService.class), new ObjectMapper())),
                PullTaskCreatorDeletionTransactionService.class);
        fixture(1, 1, 11, 111, "ENDED");
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    @ParameterizedTest @ValueSource(ints = {4, 5, 6})
    void terminalReleaseIsAtomicIdempotentAndReopensOrdinaryOnlineCommand(int status) {
        h.jdbc.update("UPDATE pull_task_group_execution SET execution_status=?", status);
        var state = h.jdbc.queryForMap("SELECT * FROM account_state WHERE account_id=1");
        assertThat(release()).isTrue();
        assertThat(release()).isFalse();
        assertReleased(1, 111);
        assertThat(h.jdbc.queryForMap("SELECT * FROM account_state WHERE account_id=1")).isEqualTo(state);
        assertThat(h.online.online(1L).accepted()).isTrue();
        assertThat(count("protocol_command_outbox")).isOne();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void recoverableExecutionsIncludingManualPauseKeepProtection(int status) {
        h.jdbc.update("UPDATE pull_task_group_execution SET execution_status=?,manual_paused=1", status);
        assertThat(release()).isFalse();
        assertProtected();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void submittedOrClosedLedgerNeverReleases(int status) {
        h.jdbc.update("UPDATE pull_task_creator_deletion SET status=?", status);
        assertThat(release()).isFalse();
        assertProtected();
    }

    @ParameterizedTest @ValueSource(strings = {"DELETING", "DELETED"})
    void irreversibleLifecycleNeverReleases(String lifecycle) {
        h.jdbc.update("UPDATE account_creator_deletion SET lifecycle=?", lifecycle);
        assertThat(release()).isFalse();
        assertProtected();
    }

    @Test void operationSubmittedTimestampAndMissingLedgerEachKeepProtection() {
        h.jdbc.update("UPDATE account_creator_deletion SET operation_id='delete'");
        assertThat(release()).isFalse();
        h.jdbc.update("UPDATE account_creator_deletion SET operation_id=NULL");
        h.jdbc.update("UPDATE pull_task_creator_deletion SET submitted_at=1");
        assertThat(release()).isFalse();
        h.jdbc.update("DELETE FROM pull_task_creator_deletion");
        assertThat(release()).isFalse();
        assertProtected();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 5, 6})
    void inFlightCommandsBlockUntilTheySettle(int status) {
        command(status, "{\"pullTaskId\":11,\"groupExecutionId\":111}");
        assertThat(release()).isFalse();
        assertProtected();
        h.jdbc.update("UPDATE protocol_command_outbox SET status=2");
        assertThat(release()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"bad\"", "true", "{}", "1.5", "9223372036854775808", "-1", "null"})
    void invalidJsonOwnershipNeverCountsAsAnotherExecution(String invalid) {
        command(5, "{\"pullTaskId\":" + invalid + ",\"groupExecutionId\":111}");
        assertThat(release()).isFalse();
        assertProtected();
    }

    @Test void otherExecutionCommandsDoNotBlockButMalformedOwnershipDoes() {
        command(5, "{\"pullTaskId\":12,\"groupExecutionId\":112}");
        assertThat(release()).isTrue();
        fixture(2, 1, 12, 112, "COMPLETED");
        h.jdbc.update("UPDATE protocol_command_outbox SET protocol_account_id='account-2',payload_json='broken'");
        assertThat(service.releaseIfTerminalUnsubmitted(1, 112, "TEST", 2000)).isFalse();
    }

    @Test void softDeletedAccountsReleaseWithoutChangingDeletionOrState() {
        h.jdbc.update("UPDATE account SET deleted_at=1234 WHERE id=1");
        assertThat(release()).isTrue();
        assertThat(h.jdbc.queryForObject("SELECT deleted_at FROM account WHERE id=1", Long.class)).isEqualTo(1234L);
        assertThat(h.jdbc.queryForObject("SELECT account_state FROM account_state", Integer.class)).isEqualTo(6);
    }

    @Test void wrongTenantAndBindingCannotAffectAnotherReservationOrRole() {
        fixture(2, 2, 12, 112, "ENDED");
        h.jdbc.update("UPDATE account SET ws_phone='15500000001' WHERE id=2");
        assertThat(service.releaseIfTerminalUnsubmitted(2, 111, "WRONG", 2000)).isFalse();
        assertThat(release()).isTrue();
        assertThat(h.jdbc.queryForList("SELECT account_id FROM account_creator_deletion", Long.class)).containsExactly(2L);
        assertThat(h.jdbc.queryForObject("SELECT released_at FROM pull_task_group_account WHERE account_id=2", Long.class)).isNull();
        assertThat(TenantContext.get()).isEqualTo(1L);
    }

    @Test void onlyOriginalCreatorRoleIsReleased() {
        h.jdbc.update("INSERT INTO pull_task_group_account(tenant_id,task_id,group_execution_id,account_id,"
                + "account_phone,role_type,role_seq,created_at,updated_at) VALUES(1,12,112,1,'test',4,1,1,1)");
        assertThat(release()).isTrue();
        assertThat(h.jdbc.queryForObject("SELECT released_at FROM pull_task_group_account WHERE group_execution_id=112", Long.class)).isNull();
    }

    @Test void releaseFirstPreventsClaimAndLateObservationFromReopeningLedger() {
        var row = ledger.selectByExecutionId(111);
        assertThat(release()).isTrue();
        row.setSubmittedAt(2000L);
        assertThat(ledger.claimSubmission(row)).isZero();
        row.setStatus(3);
        assertThat(ledger.updateObservation(row)).isZero();
        assertThat(ledger.selectByExecutionId(111).getStatus()).isEqualTo(6);
    }

    @Test void concurrentClaimCommitsBeforeWaitingReleaseAndKeepsProtection() throws Exception {
        var locked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var claim = pool.submit(() -> {
                TenantContext.set(1L);
                try { h.transactions.executeWithoutResult(tx -> {
                    session.getMapper(PullTaskGroupExecutionMapper.class).selectByIdForUpdate(111L);
                    var row = ledger.selectByExecutionIdForUpdate(111);
                    row.setSubmittedAt(2000L);
                    assertThat(ledger.claimSubmission(row)).isOne();
                    locked.countDown();
                    try { assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                }); } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var freeing = pool.submit(this::release);
            assertThatThrownBy(() -> freeing.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finish.countDown();
            claim.get(5, TimeUnit.SECONDS);
            assertThat(freeing.get(5, TimeUnit.SECONDS)).isFalse();
            assertProtected();
        } finally { finish.countDown(); pool.shutdownNow(); }
    }

    @Test void outerFailureRollsBackHistoryLedgerAndRolesTogether() {
        assertThatThrownBy(() -> h.transactions.executeWithoutResult(tx -> {
            assertThat(release()).isTrue();
            throw new IllegalStateException("later terminal hook failed");
        })).isInstanceOf(IllegalStateException.class);
        assertProtected();
        assertThat(ledger.selectByExecutionId(111).getStatus()).isZero();
        assertThat(h.jdbc.queryForObject("SELECT released_at FROM pull_task_group_account", Long.class)).isNull();
    }

    @Test void sweepHandlesBothParentTerminalsAndContinuesAfterOneTransactionFails() {
        fixture(2, 1, 12, 112, "COMPLETED");
        // 真实数据库约束触发第一条归档失败，第二条必须使用自己的事务继续提交。
        h.jdbc.execute("ALTER TABLE account_creator_deletion_release ADD CONSTRAINT fail_first CHECK(account_id<>1)");
        new PullTaskCreatorReservationReleaseJob(false, ledger, service).release();
        assertThat(count("account_creator_deletion")).isEqualTo(2);
        new PullTaskCreatorReservationReleaseJob(true, ledger, service).release();
        assertThat(count("account_creator_deletion")).isOne();
        assertReleased(2, 112);
        assertThat(ledger.selectByExecutionId(111).getStatus()).isZero();
        h.jdbc.execute("ALTER TABLE account_creator_deletion_release DROP CONSTRAINT fail_first");
        new PullTaskCreatorReservationReleaseJob(true, ledger, service).release();
        assertThat(count("account_creator_deletion")).isZero();
        assertThat(count("account_creator_deletion_release")).isEqualTo(2);
        assertThat(TenantContext.get()).isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void manualTaskAndExecutionEndReleaseWithinTheSameTransaction(boolean wholeTask) {
        h.jdbc.update("UPDATE pull_task SET status='EXECUTING'");
        h.jdbc.update("UPDATE pull_task_group_execution SET execution_status=2");
        var tasks = session.getMapper(PullTaskMapper.class);
        var executions = session.getMapper(PullTaskGroupExecutionMapper.class);
        var projection = mock(com.armada.task.service.GroupDataPackageTaskProjectionService.class);
        var completion = h.transactional(new PullTaskParentCompletionService(tasks, executions, projection,
                mock(com.armada.task.service.impl.PullTaskGroupRetryService.class), service), PullTaskParentCompletionService.class);
        var pull = new com.armada.task.service.impl.PullTaskLifecyclePullResources(
                session.getMapper(PullTaskGroupAccountMapper.class), mock(com.armada.task.mapper.PullTaskPullCallMapper.class),
                mock(com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper.class),
                mock(com.armada.task.mapper.PullTaskMaterialMemberMapper.class),
                mock(com.armada.task.mapper.PullTaskPullWaveMapper.class), projection);
        var outbox = mock(com.armada.platform.protocol.service.ProtocolCommandOutboxService.class);
        var trigger = mock(PullTaskExecutionDispatchTrigger.class);
        var actions = mock(PullTaskAccountActionMapper.class);
        var queries = mock(com.armada.task.mapper.PullTaskMemberQueryMapper.class);
        Runnable end;
        if (wholeTask) {
            var lifecycle = h.transactional(new com.armada.task.service.impl.PullTaskStandardLifecycleServiceImpl(
                    tasks, new com.armada.task.service.impl.PullTaskStandardLifecycleResources(executions,
                            actions, queries, pull, outbox, trigger, service), completion, () -> 2000L),
                    com.armada.task.service.PullTaskStandardLifecycleService.class);
            end = () -> lifecycle.end(11);
        } else {
            var lifecycle = h.transactional(new com.armada.task.service.impl.PullTaskStandardExecutionLifecycleServiceImpl(
                    tasks, new com.armada.task.service.impl.PullTaskStandardExecutionLifecycleResources(executions,
                            mock(com.armada.task.mapper.PullTaskStandardSettingMapper.class), actions, queries, pull, outbox),
                    completion, trigger, () -> 2000L), com.armada.task.service.PullTaskStandardExecutionLifecycleService.class);
            end = () -> lifecycle.end(11, 111);
        }
        assertThatThrownBy(() -> h.transactions.executeWithoutResult(tx -> {
            end.run();
            assertReleased(1, 111);
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force outer rollback");
        assertProtected();
        assertThat(ledger.selectByExecutionId(111).getStatus()).isZero();
        assertThat(h.jdbc.queryForObject("SELECT execution_status FROM pull_task_group_execution", Integer.class)).isEqualTo(2);
        end.run();
        assertReleased(1, 111);
    }

    @Test void sweepCursorAdvancesBeyondOnePageAndRestoresCallerTenant() {
        for (long id = 2; id <= 52; id++) fixture(id, id % 2 + 1, id + 20, id + 200, "COMPLETED");
        TenantContext.set(99L);
        new PullTaskCreatorReservationReleaseJob(true, ledger, service).release();
        assertThat(count("account_creator_deletion")).isZero();
        assertThat(count("account_creator_deletion_release")).isEqualTo(52);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    private boolean release() { return service.releaseIfTerminalUnsubmitted(1, 111, "TEST_TERMINAL", 2000); }
    private int count(String table) { return h.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertProtected() {
        assertThat(count("account_creator_deletion")).isOne();
        assertThat(count("account_creator_deletion_release")).isZero();
    }
    private void assertReleased(long accountId, long executionId) {
        assertThat(h.jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion_release WHERE account_id=?",
                Integer.class, accountId)).isOne();
        assertThat(h.jdbc.queryForObject("SELECT status FROM pull_task_creator_deletion WHERE group_execution_id=?",
                Integer.class, executionId)).isEqualTo(6);
        assertThat(h.jdbc.queryForObject("SELECT unavailable_reason_code FROM pull_task_group_account WHERE group_execution_id=?",
                String.class, executionId)).isEqualTo("CREATOR_RESERVATION_RELEASED");
        assertThat(h.jdbc.queryForObject("SELECT released_at FROM pull_task_group_account WHERE group_execution_id=?",
                Long.class, executionId)).isNotNull();
        assertThat(h.jdbc.queryForObject("SELECT availability_status FROM pull_task_group_account WHERE group_execution_id=?",
                Integer.class, executionId)).isEqualTo(4);
    }
    private void command(int status, String payload) {
        h.jdbc.update("INSERT INTO protocol_command_outbox(tenant_id,command_id,command_type,aggregate_type,aggregate_id,"
                + "protocol_account_id,status,payload_json) VALUES(1,'pending','account.online.requested','ACCOUNT',1,'account-1',?,?)", status, payload);
    }
    private void fixture(long account, long tenant, long task, long execution, String parentStatus) {
        h.account(account, 6, 2, 1, null);
        h.jdbc.update("UPDATE account SET tenant_id=? WHERE id=?", tenant, account);
        h.jdbc.update("UPDATE account_state SET tenant_id=? WHERE account_id=?", tenant, account);
        h.jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at) VALUES(?,?,'test','NORMAL_LINK',?,'{}',1,1)", task, tenant, parentStatus);
        h.jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,created_at,updated_at) VALUES(?,?,?,1,1,'test',6,1,1)", execution, tenant, task);
        h.jdbc.update("INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,identity_hash,creator_phone,protocol_account_id,create_operation_id,lifecycle,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'RESERVED',1,1)",
                account, tenant, task, execution, "hash-" + account, "1550000000" + account, "account-" + account, "create-" + execution);
        h.jdbc.update("INSERT INTO pull_task_creator_deletion(tenant_id,task_id,group_execution_id,creator_account_id,creator_identity_hash,creator_protocol_account_id,creator_phone,create_operation_id,operation_id,status) VALUES(?,?,?,?,?,?,?,?,?,0)",
                tenant, task, execution, account, "hash-" + account, "account-" + account, "1550000000" + account, "create-" + execution, "delete-" + execution);
        h.jdbc.update("INSERT INTO pull_task_group_account(tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,role_seq,created_at,updated_at) VALUES(?,?,?,?,?,4,1,1,1)",
                tenant, task, execution, account, "1550000000" + account);
    }
}
