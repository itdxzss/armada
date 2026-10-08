package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.dto.CreatorDeletionBinding;
import com.armada.account.model.dto.CreatorReservationRequest;
import com.armada.account.service.impl.AccountCreatorDeletionServiceImpl;
import com.armada.account.service.impl.AccountServiceImpl;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 MyBatis、租户插件、H2 MySQL 模式和独立事务验证删除账号排他边界。 */
class AccountCreatorDeletionServiceInMemoryTest {
    private AccountCreatorDeletionService service;
    private AccountCreatorDeletionMapper mapper;
    private AccountMapper accounts;
    private AccountStateMapper states;
    private JdbcTemplate db;
    private TransactionTemplate tx;

    @BeforeEach
    void setup() throws Exception {
        DataSource source = PullTaskNormalLinkH2Support.dataSource("creatorDeletion" + System.nanoTime());
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        MyBatisConfig config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/account/AccountCreatorDeletionMapper.xml", "mapper/account/AccountMapper.xml",
                "mapper/account/AccountStateMapper.xml");
        var session = new SqlSessionTemplate(factory);
        mapper = session.getMapper(AccountCreatorDeletionMapper.class);
        accounts = session.getMapper(AccountMapper.class);
        states = session.getMapper(AccountStateMapper.class);
        service = new AccountCreatorDeletionServiceImpl(mapper);
        schema();
        com.armada.testsupport.CreatorDeletionH2Schema.installIdentityIndex(source);
        TenantContext.set(7L);
        account(1, 7, "12345678901");
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void reservationIsOneShotAndGenericSelectionExcludesItWhileFrozenOwnerCanUseIt() {
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 101, 501)))).isFalse();
        assertThat(service.findReservedCreator(500)).contains(ref(1));
        assertThat(accounts.selectOnlineByGroupId(10L, List.of(2), 1)).isEmpty();
        assertThat(accounts.selectActiveById(1L)).isNotNull();
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "acc_12345678901", 100L, 500L)).isFalse();
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "acc_12345678901", 101L, 501L)).isTrue();
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "acc_12345678901", null, null)).isTrue();
        account(2, 7, "12345678901");
        db.update("UPDATE account SET protocol_account_id='alternate-route' WHERE id=2");
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "alternate-route", 101L, 501L)).isTrue();
    }

    @Test
    void duplicateIdentityAcrossAccountRowsAndTenantsIsGloballyExclusive() {
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        account(2, 8, "12345678901");
        TenantContext.set(8L);
        var other = new CreatorReservationRequest(8, 101, 501,
                new ProtocolAccountRef(2L, ProtocolBackend.ANDROID, "acc_12345678901", "12345678901"), "create-501", 2);
        assertThat(tx.<Boolean>execute(s -> service.reserve(other))).isFalse();
        assertThat(accounts.selectOnlineByGroupId(10L, List.of(2), 1)).isEmpty();
        assertThat(service.findReservedCreator(500)).isEmpty();
        assertThatThrownBy(() -> service.reserve(request(1, 100, 500))).isInstanceOf(BusinessException.class);
    }

    @Test
    void unsupportedOrOfflineCreatorsCannotBeReserved() {
        for (String update : List.of("UPDATE account SET protocol_id='WEB'",
                "UPDATE account SET device_os=2", "UPDATE account_credential SET cred_format=2",
                "UPDATE account_state SET login_state=2")) {
            db.update(update);
            assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isFalse();
            db.update("UPDATE account SET protocol_id='ANDROID',device_os=1");
            db.update("UPDATE account_credential SET cred_format=1");
            db.update("UPDATE account_state SET login_state=1");
        }
    }

    @Test
    void anyOtherActiveRoleOrTaskBlocksReserveAndBeginDeletion() {
        db.update("INSERT INTO pull_task_group_execution VALUES(501,7,2)");
        db.update("INSERT INTO pull_task_group_account VALUES(7,101,501,1,1,NULL,1,NULL,1)");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isFalse();
        db.update("DELETE FROM pull_task_group_account");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        db.update("INSERT INTO marketing_task VALUES(9,7,1,2,NULL)");
        db.update("INSERT INTO marketing_account_occupancy VALUES(1,7,9)");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        assertThat(mapper.byExecution(7, 500).getLifecycle()).isEqualTo("RESERVED");
    }

    @Test
    void activeOutboxAndChangedIdentityPreventDeletion() {
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        db.update("INSERT INTO protocol_command_outbox VALUES(7,'acc_12345678901',5,NULL)");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("DELETE FROM protocol_command_outbox");
        db.update("UPDATE account SET ws_phone='12345678902'");
        assertThat(service.findReservedCreator(500)).isEmpty();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
    }

    @Test
    void deletionBindsOneOperationAndNeverReleasesAccountBackToSelection() {
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 11))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-2"), 11))).isFalse();
        assertThat(accounts.selectActiveById(1L)).isNull();
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "acc_12345678901", 100L, 500L)).isTrue();
        assertThat(db.queryForObject("SELECT desired_login_state FROM account_state", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT account_state FROM account_state", Integer.class)).isEqualTo(2);
        tx.executeWithoutResult(s -> service.completeDeletion(binding("delete-1"), 12));
        tx.executeWithoutResult(s -> service.completeDeletion(binding("delete-1"), 13));
        assertThat(mapper.byExecution(7, 500).getLifecycle()).isEqualTo("DELETED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT login_state FROM account_state", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT account_state FROM account_state", Integer.class)).isEqualTo(9);
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 101, 501)))).isFalse();
    }

    @Test
    void confirmedCreatorCanBeSoftDeletedWithoutRemovingAuditOrAcceptingLateOnlineEvents() {
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isTrue();
        tx.executeWithoutResult(s -> service.completeDeletion(binding("delete-1"), 12));
        AccountState lateOnline = new AccountState();
        lateOnline.setAccountId(1L);
        lateOnline.setLoginState(1);
        lateOnline.setStateSource("STATE_CHANGED");
        lateOnline.setLastStateSyncTime(20L);
        lateOnline.setUpdatedAt(20L);
        assertThat(states.updateLoginState(lateOnline)).isZero();

        account(2, 7, "12345678902");
        account(3, 8, "12345678903");
        var accountService = new AccountServiceImpl(accounts, null, null);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> accountService.batchDelete(List.of(1L, 2L))))
                .isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account WHERE deleted_at IS NOT NULL", Integer.class)).isZero();
        db.update("UPDATE account_state SET account_state=3 WHERE account_id=2");
        tx.executeWithoutResult(s -> accountService.batchDelete(List.of(1L, 2L)));

        assertThat(db.queryForObject("SELECT COUNT(*) FROM account WHERE tenant_id=7 AND deleted_at IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT deleted_at FROM account WHERE id=3", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT account_state FROM account_state WHERE account_id=1", Integer.class)).isEqualTo(9);
        assertThat(mapper.byExecution(7, 500).getLifecycle()).isEqualTo("DELETED");
        assertThat(accounts.creatorDeletionCommandBlocked(7L, "acc_12345678901", null, null)).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class)).isZero();
    }

    @Test
    void concurrentExecutionRowsSerializeOnRealAccountRowLock() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                TenantContext.set(7L);
                try {
                    return tx.execute(s -> {
                        boolean reserved = service.reserve(request(1, 100, 500));
                        locked.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException e) { throw new IllegalStateException(e); }
                        return reserved;
                    });
                } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                TenantContext.set(7L);
                try { return tx.execute(s -> service.reserve(request(1, 101, 501))); }
                finally { TenantContext.clear(); }
            });
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void aliasAccountCommandsAndReservationsShareTheIdentityLock() throws Exception {
        account(2, 7, "12345678901");
        db.update("UPDATE account SET protocol_account_id='alias-route' WHERE id=2");
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                assertThat(service.reserve(request(1, 100, 500))).isTrue();
                var alias = pool.submit(() -> {
                    TenantContext.set(7L);
                    try {
                        return tx.execute(other -> service.reserve(new CreatorReservationRequest(7, 101, 501,
                                new ProtocolAccountRef(2L, ProtocolBackend.ANDROID, "alias-route", "12345678901"),
                                "create-501", 2)));
                    } finally { TenantContext.clear(); }
                });
                assertThatThrownBy(() -> alias.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(mapper.byExecution(7, 501)).isNull();
        db.update("INSERT INTO protocol_command_outbox VALUES(7,'alias-route',0,NULL)");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 3))).isFalse();
    }

    @Test
    void deletingIdentityBlocksAliasOnlineEventsTakeoverAndProxyRecoveryAcrossTenants() {
        account(2, 8, "12345678901");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isTrue();
        for (boolean completed : List.of(false, true)) {
            if (completed) {
                TenantContext.set(7L);
                tx.executeWithoutResult(s -> service.completeDeletion(binding("delete-1"), 11));
            }
            TenantContext.set(8L);
            db.update("UPDATE account_state SET account_state=1,login_state=2,desired_login_state=1,state_source='PROXY_FAILED',last_state_sync_time=1 WHERE account_id=2");
            AccountState event = new AccountState();
            event.setAccountId(2L);
            event.setLoginState(1);
            event.setAccountState(2);
            event.setLastStateSyncTime(20L);
            event.setStateSource("STATE_CHANGED");
            event.setUpdatedAt(20L);
            assertThat(states.updateLoginState(event)).isZero();
            assertThat(states.markOnlineNormalStateInternal(event, 1, 3)).isZero();
            event.setAccountState(4);
            assertThat(states.updateLifecycleState(event)).isZero();
            assertThat(states.updateLoginAndAccountState(event)).isZero();
            assertThat(states.markTakingOverByAccountIds(List.of(2L), 1, 5, 20L)).isZero();
            assertThat(states.claimProxyFailedReonline(2L, 1L, 20L)).isZero();
            assertThat(states.selectProxyFailedRecoveryCandidates(2, "PROXY_FAILED", 2, 100L, 10)).isEmpty();
            assertThat(db.queryForObject("SELECT login_state FROM account_state WHERE account_id=2", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void retainedTerminalHistoryDoesNotBlockButUnresolvedAndActiveDependenciesDo() {
        db.update("INSERT INTO marketing_task VALUES(9,7,1,3,NULL)");
        db.update("INSERT INTO marketing_account_occupancy VALUES(1,7,9)");
        db.update("INSERT INTO normal_group_creation_item VALUES(9,7,1,NULL,'CREATING_GROUP','FAILED')");
        db.update("INSERT INTO historical_group_pull_execution VALUES(1,NULL,NULL,4,0)");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        db.update("UPDATE normal_group_creation_item SET status='RESULT_UNKNOWN'");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("UPDATE normal_group_creation_item SET status='FAILED'");
        db.update("UPDATE marketing_task SET status=2");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("UPDATE marketing_task SET status=3");
        db.update("UPDATE historical_group_pull_execution SET marketing_status=2");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("UPDATE historical_group_pull_execution SET marketing_status=3");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isTrue();
    }

    @Test
    void futureScriptRolesBlockOnlyTheExactFrozenIdentityInAnActiveTask() {
        account(2, 8, "12345678901");
        db.update("INSERT INTO script_marketing_task VALUES(9,8,1,?)", "[{\"accountId\":20}]");
        db.update("INSERT INTO script_marketing_group VALUES(9,8,?)", "{\"promoter\":20}");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
        db.update("UPDATE script_marketing_group SET bindings_json=?", "{\"promoter\":2}");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("UPDATE script_marketing_group SET bindings_json='{}'");
        db.update("UPDATE script_marketing_task SET steps_json=?", "[{\"accountId\":\"2\"}]");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isFalse();
        db.update("UPDATE script_marketing_task SET status=4");
        assertThat(tx.<Boolean>execute(s -> service.beginDeletion(binding("delete-1"), 10))).isTrue();
    }

    @Test
    void malformedActiveScriptBindingCannotBeMistakenForNoDependency() {
        db.update("INSERT INTO script_marketing_task VALUES(9,7,2,'not-json')");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isFalse();
        db.update("UPDATE script_marketing_task SET status=0");
        assertThat(tx.<Boolean>execute(s -> service.reserve(request(1, 100, 500)))).isTrue();
    }

    private ProtocolAccountRef ref(long id) {
        return new ProtocolAccountRef(id, ProtocolBackend.ANDROID, "acc_12345678901", "12345678901");
    }
    private CreatorReservationRequest request(long accountId, long task, long execution) {
        return new CreatorReservationRequest(7, task, execution, ref(accountId), "create-" + execution, 1);
    }
    private CreatorDeletionBinding binding(String operation) {
        return new CreatorDeletionBinding(7, 100, 500, 1, service.identityHash(ref(1)), "create-500", operation);
    }
    private void account(long id, long tenant, String phone) {
        db.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id,account_group_id,protocol_id,device_os,deleted_at) VALUES(?,?,?,?,10,'ANDROID',1,NULL)", id, tenant, phone, "acc_" + phone);
        db.update("INSERT INTO account_state(account_id,tenant_id,login_state,account_state,desired_login_state,updated_at,last_state_sync_time) VALUES(?,?,1,2,1,1,1)", id, tenant);
        db.update("INSERT INTO account_credential VALUES(?,?,1,NULL)", id, tenant);
    }
    private void schema() {
        db.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,ws_phone VARCHAR(32),protocol_account_id VARCHAR(128),account_group_id BIGINT,protocol_id VARCHAR(16),device_os INT,deleted_at BIGINT,dispatched_at BIGINT)");
        db.execute("CREATE TABLE account_state(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,last_state_sync_time BIGINT,account_id BIGINT,tenant_id BIGINT,login_state INT,offline_since BIGINT,account_state INT,desired_login_state INT,risk_status INT,mute_status INT,state_source VARCHAR(32),invalidated_at BIGINT,updated_at BIGINT)");
        db.execute("CREATE TABLE account_credential(account_id BIGINT,tenant_id BIGINT,cred_format INT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_creator_deletion(account_id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,task_id BIGINT NOT NULL,group_execution_id BIGINT NOT NULL,identity_hash CHAR(64) NOT NULL UNIQUE,creator_phone VARCHAR(32),protocol_account_id VARCHAR(128),create_operation_id VARCHAR(128),operation_id VARCHAR(128) UNIQUE,lifecycle VARCHAR(16),created_at BIGINT,updated_at BIGINT,completed_at BIGINT,UNIQUE(tenant_id,group_execution_id))");
        db.execute("CREATE TABLE pull_task_group_execution(id BIGINT,tenant_id BIGINT,execution_status INT)");
        db.execute("CREATE TABLE pull_task_group_account(tenant_id BIGINT,task_id BIGINT,group_execution_id BIGINT,account_id BIGINT,role_type INT,released_at BIGINT,availability_status INT,unavailable_reason_code VARCHAR(64),updated_at BIGINT)");
        db.execute("CREATE TABLE marketing_account_occupancy(account_id BIGINT,tenant_id BIGINT,marketing_task_id BIGINT)");
        db.execute("CREATE TABLE marketing_task(id BIGINT,tenant_id BIGINT,business_type INT,status INT,deleted_at BIGINT)");
        db.execute("CREATE TABLE group_pull_marketing_task(marketing_task_id BIGINT,tenant_id BIGINT,resource_status INT)");
        db.execute("CREATE TABLE join_task_result(account_id BIGINT,tenant_id BIGINT,join_task_id BIGINT)");
        db.execute("CREATE TABLE join_task(id BIGINT,tenant_id BIGINT,deleted_at BIGINT,status VARCHAR(16))");
        db.execute("CREATE TABLE hyperlink_task_account_usage(account_id BIGINT,tenant_id BIGINT,hyperlink_task_id BIGINT,in_flight_count INT)");
        db.execute("CREATE TABLE hyperlink_task_runtime(hyperlink_task_id BIGINT,tenant_id BIGINT,run_status INT)");
        db.execute("CREATE TABLE feed_task_account(account_id BIGINT,tenant_id BIGINT,task_id BIGINT)");
        db.execute("CREATE TABLE feed_task(id BIGINT,tenant_id BIGINT,deleted_at BIGINT,task_status INT)");
        db.execute("CREATE TABLE account_mutual_contact_item(actor_id BIGINT,target_id BIGINT,status INT)");
        db.execute("CREATE TABLE normal_group_creation_item(id BIGINT,tenant_id BIGINT,creator_account_id BIGINT,deleted_at BIGINT,current_step VARCHAR(32),status VARCHAR(24))");
        db.execute("CREATE TABLE normal_group_creation_item_member(item_id BIGINT,tenant_id BIGINT,member_account_id BIGINT)");
        db.execute("CREATE TABLE contact_friend_task_account(account_id BIGINT,tenant_id BIGINT,task_id BIGINT)");
        db.execute("CREATE TABLE contact_friend_task(id BIGINT,tenant_id BIGINT,deleted_at BIGINT,run_status INT)");
        db.execute("CREATE TABLE group_pull_marketing_execution(builder_account_id BIGINT,marketing_account_id BIGINT,execution_status INT)");
        db.execute("CREATE TABLE historical_group_pull_execution(operation_account_id BIGINT,puller_account_id BIGINT,finished_at BIGINT,pull_status INT,marketing_status INT)");
        db.execute("CREATE TABLE script_marketing_task(id BIGINT,tenant_id BIGINT,status INT,steps_json VARCHAR(4096))");
        db.execute("CREATE TABLE script_marketing_group(task_id BIGINT,tenant_id BIGINT,bindings_json VARCHAR(4096))");
        db.execute("CREATE TABLE script_marketing_send_record(account_id BIGINT,status INT)");
        db.execute("CREATE TABLE protocol_command_outbox(tenant_id BIGINT,protocol_account_id VARCHAR(128),status INT,deleted_at BIGINT)");
    }
}
