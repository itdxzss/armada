package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper XML 验证 recipient 变化的有界、并发安全指标投影。 */
@SpringJUnitConfig(HyperlinkMetricsProjectionH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkMetricsProjectionH2Test {
    private static final int CONCURRENT_RECIPIENTS = 600;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkMetricsProjectionService service;
    @Autowired
    private HyperlinkTaskRecipientMapper recipients;
    @Autowired
    private HyperlinkTaskRuntimeMapper runtimes;
    @Autowired
    private com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper usages;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS", runtimeSchema(), roundSchema(), recipientSchema(),
                accountStatSchema(), accountUsageSchema());
    }

    @ParameterizedTest
    @CsvSource({"1", "3"})
    void timedOutAttemptMovesToAnotherSenderWithoutFailureOrDuplicateMetrics(int runStatus) throws SQLException {
        insertRetryFixture(true);
        execute("UPDATE hyperlink_task_runtime SET run_status=" + runStatus + " WHERE hyperlink_task_id=11",
                "UPDATE hyperlink_task_recipient SET command_id='hl:7:11:1' WHERE id=1",
                "INSERT INTO hyperlink_task_account_usage (tenant_id,hyperlink_task_id,account_id,usage_status,"
                + "in_flight_count,reserved_success_slot_count,successful_send_count,success_limit,version) "
                + "VALUES (7,11,41,3,2,2,0,0,1)",
                "CREATE TABLE hyperlink_recipient_sender_rejection (tenant_id BIGINT,recipient_id BIGINT,"
                + "account_id BIGINT,reason_code VARCHAR(64),created_at BIGINT,PRIMARY KEY(tenant_id,recipient_id,account_id))");
        service.projectNextBatch();
        var port = org.mockito.Mockito.mock(com.armada.platform.protocol.port.MessageCommandRecoveryPort.class);
        var guard = org.mockito.Mockito.mock(HyperlinkAccountDispatchGuard.class);
        var recovery = new HyperlinkUnknownResultRecoveryService(recipients, port, guard, usages, service, runtimes,
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(200_000L), java.time.ZoneOffset.UTC));
        var candidate = new com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate(
                7L, 11L, 1L, 41L, "hl:7:11:1", 2, 80_000L);
        inTransaction(() -> recovery.recover(candidate));
        inTransaction(() -> recovery.recover(candidate));

        assertThat(queryRow("SELECT send_status,dispatch_attempt,COALESCE(account_id,0) "
                + "FROM hyperlink_task_recipient WHERE id=1")).containsExactly(1L, 2L, 0L);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_recipient WHERE command_id IS NULL"))
                .isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_recipient_sender_rejection WHERE account_id=41"))
                .isEqualTo(1);
        assertThat(queryLong("SELECT in_flight_count FROM hyperlink_task_account_usage WHERE account_id=41"))
                .isEqualTo(1);
        assertThat(taskMetrics()).containsExactly(1L, 0L, 0L);
        assertThat(queryLong("SELECT run_status FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .isEqualTo(runStatus);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        restore(22L, 42L, "hl:7:11:1:2", true);
        inTransaction(() -> recovery.recover(candidate));
        var results = new HyperlinkProtocolResultService(recipients, usages, new HyperlinkRecipientStateMachine(),
                org.mockito.Mockito.mock(com.armada.hyperlink.data.service.DataPackageRecipientClaimService.class),
                guard, org.mockito.Mockito.mock(com.armada.account.service.AccountOperationRestrictionService.class), service);
        inTransaction(() -> results.handleAck(new com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent(
                "late",7L,"hyperlink_task",11L,1L,"hl:7:11:1",41L,"android","acc41","recipient",
                "PRIVATE","old-message","READ",true,null,null,200_001L,"worker")));
        assertThat(queryLong("SELECT send_status FROM hyperlink_task_recipient WHERE id=1")).isEqualTo(2);
        succeed("hl:7:11:1:2");
        service.projectNextBatch();
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.times(1))
                .releaseAfterCommit(41L, "hl:7:11:1", 11L, 1L);
        org.mockito.Mockito.verifyNoInteractions(port);
    }

    @Test
    void twoWorkersWithOverlappingCandidatesNeverDoubleProject() throws Exception {
        insertRuntimeAndRound(7L, 11L, 21L);
        insertSuccessfulRecipients(CONCURRENT_RECIPIENTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> projectAfter(start));
            Future<Integer> second = workers.submit(() -> projectAfter(start));
            start.countDown();

            int firstCount = first.get(10, TimeUnit.SECONDS);
            int secondCount = second.get(10, TimeUnit.SECONDS);
            assertThat(firstCount).isBetween(0, HyperlinkMetricsProjectionService.BATCH_SIZE);
            assertThat(secondCount).isBetween(0, HyperlinkMetricsProjectionService.BATCH_SIZE);
            int concurrentlyProjected = firstCount + secondCount;
            assertThat(concurrentlyProjected)
                    .isBetween(HyperlinkMetricsProjectionService.BATCH_SIZE, CONCURRENT_RECIPIENTS);
            assertThat(concurrentlyProjected + service.projectNextBatch())
                    .isEqualTo(CONCURRENT_RECIPIENTS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(queryLong("SELECT send_total FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT success_num FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT send_total FROM hyperlink_task_account_stat "
                + "WHERE hyperlink_task_id=11 AND account_id=41"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_recipient "
                + "WHERE send_status<>metrics_projected_status")).isZero();

        assertThat(service.projectNextBatch()).isZero();
        assertThat(queryLong("SELECT success_num FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
    }

    @Test
    void statusTransitionsAreMonotonicAndUnsubmittedFailuresDoNotIncreaseSendTotal()
            throws SQLException {
        insertRuntimeAndRound(7L, 11L, 21L);
        execute("INSERT INTO hyperlink_task_account_usage "
                + "(tenant_id,hyperlink_task_id,account_id,invalid_at) VALUES (7,11,41,900)");
        execute("INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,"
                        + "send_status,metrics_projected_status,submitted_at,metrics_projected_at,"
                        + "created_at,updated_at) VALUES "
                        + "(1,7,11,NULL,NULL,6,1,NULL,NULL,100,200),"
                        + "(2,7,11,21,41,3,1,1000,NULL,100,201),"
                        + "(3,7,11,21,41,4,3,1100,150,100,202),"
                        + "(4,7,11,21,41,5,4,1200,160,100,203)");

        assertThat(service.projectNextBatch()).isEqualTo(4);

        assertThat(queryRow("SELECT send_total,success_num,delivered_num,read_num,fail_num,fail_404_num,"
                + "used_account_count,invalid_account_count,last_send_at "
                + "FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 0L, 1L, 1L, 1_000L);
        assertThat(queryRow("SELECT assigned_recipient_count,send_total,success_num,delivered_num,"
                + "read_num,fail_num,last_send_at FROM hyperlink_task_round WHERE id=21"))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 0L, 1_000L);
        assertThat(queryRow("SELECT send_total,success_num,delivered_num,read_num,failed_num,"
                + "first_send_at,last_send_at "
                + "FROM hyperlink_task_account_stat WHERE hyperlink_task_id=11 AND account_id=41"))
                .containsExactly(1L, 1L, 1L, 1L, 0L, 1_000L, 1_000L);
        assertThat(queryRow("SELECT send_total,failed_num FROM hyperlink_task_account_stat "
                + "WHERE hyperlink_task_id=11 AND account_id IS NULL"))
                .containsExactly(0L, 1L);
    }

    private int projectAfter(CountDownLatch start) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待并发投影开始超时");
        }
        return service.projectNextBatch();
    }

    @Test
    void lateDeliveryReversesTimeoutFailureWithoutCountingTheSendTwice() throws SQLException {
        insertRuntimeAndRound(7L, 11L, 21L);
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,command_id,"
                + "send_status,metrics_projected_status,submitted_at,created_at,updated_at,fail_code) "
                + "VALUES (1,7,11,21,41,'hl:7:11:1',6,1,1000,100,200,'SEND_RESULT_TIMEOUT')");
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(taskMetrics()).containsExactly(1L, 0L, 1L);
        inTransaction(() -> {
            HyperlinkTaskRecipient correction = recipients.selectByCommandId("hl:7:11:1");
            correction.setSendStatus(4);
            correction.setProtocolMessageId("late-delivery");
            correction.setUpdatedAt(2000L);
            assertThat(recipients.correctTimedOutResult(correction)).isEqualTo(1);
            assertThat(recipients.correctTimedOutResult(correction)).isZero();
        });
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(service.projectNextBatch()).isZero();
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        assertThat(queryRow("SELECT send_total,success_num,delivered_num,fail_num FROM hyperlink_task_round WHERE id=21"))
                .containsExactly(1L, 1L, 1L, 0L);
        assertThat(queryRow("SELECT send_total,success_num,delivered_num,failed_num FROM hyperlink_task_account_stat WHERE account_id=41"))
                .containsExactly(1L, 1L, 1L, 0L);
    }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    @Test
    void retryProjectionEntryPointsRequireAnExistingTransaction() {
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        assertThatThrownBy(() -> service.lockRetryScope(recipient))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.requeueSystemFailure(recipient))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.restoreRetryAssignment(recipient, 1_000L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void retryMovesOnlyAttributionAcrossAccountsAndRounds(boolean projected, boolean submitted)
            throws SQLException {
        insertRetryFixture(submitted);
        if (projected) { assertThat(service.projectNextBatch()).isEqualTo(1); }

        requeue("attempt-1");
        long initialSent = submitted ? 1L : 0L;
        assertThat(taskMetrics()).containsExactly(initialSent, 0L, 0L);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_account_stat WHERE account_id=41"))
                .isZero();
        assertThat(queryLong("SELECT COALESCE(SUM(send_total),0) FROM hyperlink_task_account_stat "
                + "WHERE account_id IS NULL")).isEqualTo(initialSent);
        assertThat(service.projectNextBatch()).isZero();

        restore(22L, 42L, "attempt-2", true);
        succeed("attempt-2");
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(service.projectNextBatch()).isZero();
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        assertThat(roundMetrics(22L)).containsExactly(1L, 1L, 1L);
        assertThat(queryRow("SELECT send_total,success_num FROM hyperlink_task_account_stat "
                + "WHERE hyperlink_task_id=11 AND account_id=42")).containsExactly(1L, 1L);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_account_stat WHERE account_id IS NULL"))
                .isZero();
        assertThat(queryLong("SELECT SUM(send_total) FROM hyperlink_task_account_stat WHERE hyperlink_task_id=11"))
                .isEqualTo(1L);
    }

    @Test
    void fourth463FailureProjectsOneLogicalFailureAfterThreeRequeues() throws SQLException {
        insertRetryFixture(true);
        for (int attempt = 1; attempt <= 3; attempt++) {
            requeue("attempt-" + attempt);
            restore(22L, 42L, "attempt-" + (attempt + 1), true);
        }
        TenantContext.set(7L);
        try {
            inTransaction(() -> {
                HyperlinkTaskRecipient row = recipients.selectByCommandId("attempt-4");
                assertThat(row.getDispatchAttempt()).isEqualTo(4);
                row.setSendStatus(6);
                row.setFailCode("WA_ACK_REJECTED_463");
                row.setFailReason("WhatsApp rejected 463");
                row.setUpdatedAt(9000L);
                assertThat(recipients.applyResult(row)).isEqualTo(1);
                assertThat(recipients.applyResult(row)).isZero();
            });
        } finally {
            TenantContext.clear();
        }
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(service.projectNextBatch()).isZero();
        assertThat(queryRow("SELECT send_total,success_num,fail_num FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .containsExactly(1L, 0L, 1L);
        assertThat(queryRow("SELECT send_total,failed_num FROM hyperlink_task_account_stat WHERE account_id=42"))
                .containsExactly(1L, 1L);
        assertThat(queryLong("SELECT fail_num FROM hyperlink_task_round WHERE id=22")).isEqualTo(1);
    }

    @Test
    void repeatedRecoveryAndDuplicateOldAttemptDoNotDoubleMoveCounters() throws SQLException {
        insertRetryFixture(true);
        service.projectNextBatch();
        HyperlinkTaskRecipient oldAttempt = requeue("attempt-1");
        assertThatThrownBy(() -> inTransaction(() -> {
            service.lockRetryScope(oldAttempt);
            service.requeueSystemFailure(oldAttempt);
        })).isInstanceOf(BusinessException.class);
        assertThat(taskMetrics()).containsExactly(1L, 0L, 0L);
        assertThat(queryLong("SELECT send_total FROM hyperlink_task_account_stat WHERE account_id IS NULL"))
                .isEqualTo(1L);

        restore(22L, 42L, "attempt-2", true);
        requeue("attempt-2");
        restore(22L, 42L, "attempt-3", true);
        succeed("attempt-3");
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        assertThat(roundMetrics(22L)).containsExactly(1L, 1L, 1L);
        assertThat(queryRow("SELECT send_total,success_num FROM hyperlink_task_account_stat WHERE account_id=42"))
                .containsExactly(1L, 1L);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void repeatedLocalRejectionBeforeSubmitDoesNotLoseFirstSubmission(boolean previouslySubmitted)
            throws SQLException {
        insertRetryFixture(previouslySubmitted);
        requeue("attempt-1");
        for (int attempt = 2; attempt <= 3; attempt++) {
            String commandId = "attempt-" + attempt;
            restore(22L, 42L, commandId, false);
            requeue(commandId);
            assertThat(service.projectNextBatch()).isZero();
            assertThat(taskMetrics()).containsExactly(previouslySubmitted ? 1L : 0L, 0L, 0L);
            assertThat(roundMetrics(22L)).containsExactly(0L, 0L, 0L);
        }
        restore(22L, 42L, "attempt-4", true);
        succeed("attempt-4");
        assertThat(service.projectNextBatch()).isEqualTo(1);
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        assertThat(roundMetrics(22L)).containsExactly(1L, 1L, 1L);
        assertThat(queryRow("SELECT send_total,success_num FROM hyperlink_task_account_stat WHERE account_id=42"))
                .containsExactly(1L, 1L);
    }

    @Test
    void failedOuterTransactionRollsBackBothRecipientAndAllProjectionChanges() throws SQLException {
        insertRetryFixture(true);
        assertThatThrownBy(() -> inTransaction(() -> {
            requeueLocked("attempt-1");
            throw new IllegalStateException("rollback after moving to pending bucket");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(taskMetrics()).containsExactly(0L, 0L, 0L);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_account_stat")).isZero();
        assertThat(queryRow("SELECT send_status,metrics_projected_status,account_id,hyperlink_task_round_id "
                + "FROM hyperlink_task_recipient WHERE id=1")).containsExactly(2L, 1L, 41L, 21L);

        requeue("attempt-1");
        assertThatThrownBy(() -> inTransaction(() -> {
            restoreLocked(22L, 42L, "attempt-2", true);
            throw new IllegalStateException("rollback after restoring assignment");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(taskMetrics()).containsExactly(1L, 0L, 0L);
        assertThat(roundMetrics(22L)).containsExactly(0L, 0L, 0L);
        assertThat(queryLong("SELECT send_status FROM hyperlink_task_recipient WHERE id=1")).isEqualTo(1L);
        assertThat(queryRow("SELECT account_bucket_key,send_total FROM hyperlink_task_account_stat"))
                .containsExactly(0L, 1L);
        restore(22L, 42L, "attempt-2", true);
        assertThat(roundMetrics(22L)).containsExactly(1L, 1L, 0L);
    }

    @Test
    void pendingReconciliationAndDuplicateAssignmentKeepUniqueSendCount() throws SQLException {
        insertRetryFixture(true);
        requeue("attempt-1");
        inTransaction(() -> service.reconcile(11L));
        assertThat(taskMetrics()).containsExactly(1L, 0L, 0L);
        assertThat(queryLong("SELECT metrics_projected_status FROM hyperlink_task_recipient WHERE id=1"))
                .isEqualTo(2L);
        restore(22L, 42L, "attempt-2", true);
        assertThatThrownBy(() -> restore(22L, 42L, "attempt-2", true))
                .isInstanceOf(AssertionError.class);
        succeed("attempt-2");
        service.projectNextBatch();
        assertThat(taskMetrics()).containsExactly(1L, 1L, 0L);
        assertThat(roundMetrics(22L)).containsExactly(1L, 1L, 1L);
        assertThat(queryRow("SELECT send_total,success_num FROM hyperlink_task_account_stat WHERE account_id=42"))
                .containsExactly(1L, 1L);
    }

    @Test
    void projectionWaitsForRetryScopeAndDoesNotReapplyDetachedAttribution() throws Exception {
        insertRetryFixture(true);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> retry = workers.submit(() -> inTransaction(() -> {
                service.lockRetryScope(recipients.selectByCommandId("attempt-1"));
                locked.countDown();
                awaitSignal(release);
                requeueLocked("attempt-1");
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> projection = workers.submit(service::projectNextBatch);
            assertThatThrownBy(() -> projection.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            retry.get(5, TimeUnit.SECONDS);
            assertThat(projection.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
        assertThat(taskMetrics()).containsExactly(1L, 0L, 0L);
        assertThat(roundMetrics(21L)).containsExactly(0L, 0L, 0L);
        assertThat(queryRow("SELECT account_bucket_key,send_total FROM hyperlink_task_account_stat"))
                .containsExactly(0L, 1L);
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("等待重试事务释放超时"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    void movingBoundarySubmissionRecomputesOldAccountAndRoundTimesOnly() throws SQLException {
        insertRetryFixture(true);
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,send_status,"
                + "metrics_projected_status,submitted_at,created_at,updated_at) "
                + "VALUES (2,7,11,21,41,3,1,800,NULL,201)");
        service.projectNextBatch();
        requeue("attempt-1");
        assertThat(queryRow("SELECT send_total,success_num,first_send_at,last_send_at "
                + "FROM hyperlink_task_account_stat WHERE account_id=41"))
                .containsExactly(1L, 1L, 800L, 800L);
        assertThat(queryRow("SELECT assigned_recipient_count,send_total,last_send_at "
                + "FROM hyperlink_task_round WHERE id=21")).containsExactly(1L, 1L, 800L);
        assertThat(taskMetrics()).containsExactly(2L, 1L, 0L);
        restore(22L, 42L, "attempt-2", true);
        assertThat(queryRow("SELECT send_total,first_send_at,last_send_at "
                + "FROM hyperlink_task_account_stat WHERE account_id=42"))
                .containsExactly(1L, 1_000L, 1_000L);
    }

    @Test
    void movingRetryLeavesAnotherTenantsSameAccountBucketUntouched() throws SQLException {
        insertRetryFixture(true);
        insertRuntimeAndRound(8L, 12L, 23L);
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,send_status,"
                + "metrics_projected_status,submitted_at,created_at,updated_at) "
                + "VALUES (2,8,12,23,41,3,1,900,100,201)");
        assertThat(service.projectNextBatch()).isEqualTo(2);
        requeue("attempt-1");
        restore(22L, 42L, "attempt-2", true);
        assertThat(queryRow("SELECT send_total,success_num,used_account_count "
                + "FROM hyperlink_task_runtime WHERE tenant_id=8 AND hyperlink_task_id=12"))
                .containsExactly(1L, 1L, 1L);
        assertThat(queryRow("SELECT send_total,success_num,first_send_at,last_send_at "
                + "FROM hyperlink_task_account_stat WHERE tenant_id=8 AND account_id=41"))
                .containsExactly(1L, 1L, 900L, 900L);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_account_stat "
                + "WHERE tenant_id=8 AND account_id IS NULL")).isZero();
        assertThat(queryRow("SELECT account_id,hyperlink_task_round_id,send_status "
                + "FROM hyperlink_task_recipient WHERE id=2")).containsExactly(41L, 23L, 3L);
    }

    private void insertRetryFixture(boolean submitted) throws SQLException {
        insertRuntimeAndRound(7L, 11L, 21L);
        execute("INSERT INTO hyperlink_task_round (id,tenant_id,hyperlink_task_id) VALUES (22,7,11)",
                "INSERT INTO hyperlink_task_recipient (id,tenant_id,hyperlink_task_id,"
                + "hyperlink_task_round_id,account_id,command_id,send_status,metrics_projected_status,"
                + "submitted_at,created_at,updated_at) VALUES (1,7,11,21,41,'attempt-1',2,1,"
                + (submitted ? "1000" : "NULL") + ",100,200)");
    }

    private HyperlinkTaskRecipient requeue(String commandId) {
        HyperlinkTaskRecipient[] result = new HyperlinkTaskRecipient[1];
        inTransaction(() -> result[0] = requeueLocked(commandId));
        return result[0];
    }

    private HyperlinkTaskRecipient requeueLocked(String commandId) {
        HyperlinkTaskRecipient observed = recipients.selectByCommandId(commandId);
        service.lockRetryScope(observed);
        HyperlinkTaskRecipient recipient = recipients.selectByIdentityForUpdate(7L, 11L, 1L, commandId);
        recipient.setUpdatedAt(2_000L);
        recipient.setNextDispatchAt(2_000L);
        recipient.setFailCode("RECIPIENT_SESSION_UNAVAILABLE");
        service.requeueSystemFailure(recipient);
        return recipient;
    }

    private void restore(long roundId, long accountId, String commandId, boolean submit) {
        inTransaction(() -> restoreLocked(roundId, accountId, commandId, submit));
    }

    private void restoreLocked(long roundId, long accountId, String commandId, boolean submit) {
        HyperlinkTaskRecipient recipient = recipients.selectCurrentByIdentity(7L, 11L, 1L);
        recipient.setHyperlinkTaskRoundId(roundId);
        service.lockRetryScope(recipient);
        recipient.setAccountId(accountId);
        recipient.setRoundNo(2L);
        recipient.setCommandId(commandId);
        recipient.setUpdatedAt(3_000L);
        recipient.setNextDispatchAt(3_000L);
        assertThat(recipients.assignCommand(recipient)).isEqualTo(1);
        recipient.setSendStatus(2);
        service.restoreRetryAssignment(recipient, 3_000L);
        if (submit) { assertThat(recipients.markSubmitted(commandId, 3_000L, 4_000L)).isEqualTo(1); }
    }

    private void succeed(String commandId) {
        inTransaction(() -> {
            HyperlinkTaskRecipient recipient = recipients.selectByCommandId(commandId);
            recipient.setSendStatus(3);
            recipient.setUpdatedAt(4_000L);
            assertThat(recipients.applyResult(recipient)).isEqualTo(1);
        });
    }

    private void inTransaction(Runnable action) {
        TenantContext.set(7L);
        try { new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run()); }
        finally { TenantContext.clear(); }
    }

    private java.util.List<Long> taskMetrics() throws SQLException {
        return queryRow("SELECT send_total,success_num,fail_num FROM hyperlink_task_runtime WHERE hyperlink_task_id=11");
    }

    private java.util.List<Long> roundMetrics(long roundId) throws SQLException {
        return queryRow("SELECT assigned_recipient_count,send_total,success_num FROM hyperlink_task_round WHERE id=" + roundId);
    }

    @Test
    void fullReconciliationMarksPendingRetrySubmissionAsAlreadyCounted() throws SQLException {
        insertRuntimeAndRound(7L, 11L, 21L);
        execute("INSERT INTO hyperlink_task_recipient (id,tenant_id,hyperlink_task_id,send_status,metrics_projected_status,submitted_at,created_at,updated_at) VALUES (1,7,11,1,1,1000,100,200)");
        com.armada.shared.tenant.TenantContext.set(7L);
        try {
            recipients.markProjected(11L, 300L);
        } finally {
            com.armada.shared.tenant.TenantContext.clear();
        }
        assertThat(queryLong("SELECT metrics_projected_status FROM hyperlink_task_recipient WHERE id=1"))
                .isEqualTo(2L);
    }

    private void insertRuntimeAndRound(long tenantId, long taskId, long roundId) throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(tenant_id,hyperlink_task_id,send_total,success_num,delivered_num,read_num,"
                        + "fail_num,fail_404_num,created_at,updated_at) VALUES ("
                        + tenantId + "," + taskId + ",0,0,0,0,0,0,100,100)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,assigned_recipient_count,send_total,"
                        + "success_num,delivered_num,read_num,fail_num,fail_404_num,created_at,updated_at) "
                        + "VALUES (" + roundId + "," + tenantId + "," + taskId
                        + ",0,0,0,0,0,0,0,100,100)");
    }

    private void insertSuccessfulRecipients(int count) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO hyperlink_task_recipient "
                             + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,"
                             + "send_status,metrics_projected_status,submitted_at,created_at,updated_at) "
                             + "VALUES (?,7,11,21,41,3,1,1000,100,?)")) {
            for (int id = 1; id <= count; id++) {
                statement.setInt(1, id);
                statement.setInt(2, 100 + id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private long queryLong(String sql) throws SQLException {
        return queryRow(sql).get(0);
    }

    private java.util.List<Long> queryRow(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            java.util.List<Long> values = new java.util.ArrayList<>();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                values.add(result.getLong(column));
            }
            return values;
        }
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private String runtimeSchema() {
        return "CREATE TABLE hyperlink_task_runtime (tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT PRIMARY KEY, send_total BIGINT DEFAULT 0, "
                + "success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, fail_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "used_account_count INT DEFAULT 0, invalid_account_count INT DEFAULT 0, "
                + "recipient_total BIGINT DEFAULT 0, actual_concurrency INT DEFAULT 0, "
                + "run_status INT DEFAULT 1, "
                + "last_send_at BIGINT, metrics_updated_at BIGINT, created_at BIGINT, updated_at BIGINT)";
    }

    private String roundSchema() {
        return "CREATE TABLE hyperlink_task_round (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT NOT NULL, assigned_recipient_count INT DEFAULT 0, "
                + "send_total BIGINT DEFAULT 0, success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, fail_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "round_no INT DEFAULT 1, actual_concurrency INT DEFAULT 0, "
                + "last_send_at BIGINT, created_at BIGINT, updated_at BIGINT)";
    }

    private String recipientSchema() {
        return "CREATE TABLE hyperlink_task_recipient (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT NOT NULL, hyperlink_task_round_id BIGINT, account_id BIGINT, "
                + "send_status TINYINT NOT NULL, metrics_projected_status TINYINT NOT NULL, "
                + "needs_metrics_projection TINYINT GENERATED ALWAYS AS "
                + "(CASE WHEN send_status<>metrics_projected_status THEN 1 ELSE NULL END), "
                + "submitted_at BIGINT, metrics_projected_at BIGINT, created_at BIGINT, updated_at BIGINT, "
                + "round_no INT, dispatch_attempt INT NOT NULL DEFAULT 1, command_id VARCHAR(128), "
                + "sender_phone_snapshot VARCHAR(64), sender_country_iso2_snapshot VARCHAR(8), "
                + "sender_account_type_snapshot INT, sender_device_os_snapshot INT, protocol_id VARCHAR(64), "
                + "protocol_backend INT, protocol_message_id VARCHAR(128), short_code VARCHAR(64), "
                + "next_dispatch_at BIGINT DEFAULT 0, fail_code VARCHAR(64), fail_reason VARCHAR(255), "
                + "failed_at BIGINT, sent_at BIGINT, delivered_at BIGINT, read_at BIGINT, "
                + "INDEX idx_projection(needs_metrics_projection,tenant_id,hyperlink_task_id,updated_at,id))";
    }

    private String accountStatSchema() {
        return "CREATE TABLE hyperlink_task_account_stat (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT, "
                + "account_bucket_key BIGINT GENERATED ALWAYS AS (COALESCE(account_id,0)), "
                + "send_total BIGINT DEFAULT 0, success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, failed_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "first_send_at BIGINT, last_send_at BIGINT, created_at BIGINT, updated_at BIGINT, "
                + "reconciled_at BIGINT, UNIQUE(tenant_id,hyperlink_task_id,account_bucket_key))";
    }

    private String accountUsageSchema() {
        return "CREATE TABLE hyperlink_task_account_usage (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT NOT NULL, "
                + "invalid_at BIGINT, usage_status INT DEFAULT 1, in_flight_count INT DEFAULT 0, "
                + "reserved_success_slot_count INT DEFAULT 0,successful_send_count INT DEFAULT 0, "
                + "success_limit INT DEFAULT 0,version INT DEFAULT 1,updated_at BIGINT)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_metrics_projection_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new Resource[] {
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountStatMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountUsageMapper.xml")
            });
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRecipientMapper.class);
        }
        @Bean HyperlinkTaskRuntimeMapper runtimeMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRuntimeMapper.class);
        }
        @Bean HyperlinkTaskRoundMapper roundMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRoundMapper.class);
        }
        @Bean HyperlinkTaskAccountStatMapper accountStatMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskAccountStatMapper.class);
        }
        @Bean com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper usageMapper(SqlSessionTemplate value) {
            return value.getMapper(com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper.class);
        }
        @Bean
        HyperlinkMetricsProjectionService projectionService(HyperlinkTaskRecipientMapper recipients,
                HyperlinkTaskRuntimeMapper runtimes, HyperlinkTaskRoundMapper rounds,
                HyperlinkTaskAccountStatMapper accountStats) {
            return new HyperlinkMetricsProjectionService(recipients, runtimes, rounds, accountStats);
        }
    }
}
