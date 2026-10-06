package com.armada.marketing.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.MarketingNewGroupDelayUnit;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 SQL、租户插件和事务验证营销首轮等待与新群首发后的统一轮次。 */
class MarketingFirstSendDelayMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 42L;
    private static final long TARGET_ID = 701L;
    private static final long ACCOUNT_ID = 501L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long START_AT = 17 * HOUR;

    private MarketingTaskMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:marketing_first_send_delay;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTaskSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MyBatisConfig productionConfig = new MyBatisConfig();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(productionConfig.mybatisPlusInterceptor(productionConfig.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MarketingTaskMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void manualStartWaitsFullConfiguredMinutesFromTaskStart() {
        insertTask(MarketingTaskStatus.PENDING, true, 10, MarketingNewGroupDelayUnit.MINUTE);

        transaction.executeWithoutResult(status ->
                assertThat(mapper.startPendingTask(TASK_ID, START_AT)).isEqualTo(1));

        assertTiming(START_AT, START_AT + 10 * MINUTE);
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.claimDueRound(TASK_ID, START_AT + 9 * MINUTE, START_AT + HOUR)).isZero();
            assertThat(mapper.claimDueRound(TASK_ID, START_AT + 10 * MINUTE, START_AT + HOUR))
                    .isEqualTo(1);
        });
    }

    @Test
    void scheduledStartWaitsFullConfiguredHour() {
        insertTask(MarketingTaskStatus.PENDING, true, 1, MarketingNewGroupDelayUnit.HOUR);

        transaction.executeWithoutResult(status -> {
            assertThat(mapper.startDueWaitingTask(TASK_ID, START_AT - 1)).isZero();
            assertThat(mapper.startDueWaitingTask(TASK_ID, START_AT)).isEqualTo(1);
        });

        assertTiming(START_AT, 18 * HOUR);
    }

    @Test
    void resumeBeforeFirstDeadlinePreservesRemainingWait() {
        insertTask(MarketingTaskStatus.PENDING, true, 1, MarketingNewGroupDelayUnit.HOUR);
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.startPendingTask(TASK_ID, START_AT)).isEqualTo(1);
            assertThat(mapper.pauseSendingTask(TASK_ID, START_AT + 10 * MINUTE)).isEqualTo(1);
            assertThat(mapper.resumePausedTask(TASK_ID, START_AT + 30 * MINUTE)).isEqualTo(1);
        });

        assertTiming(START_AT, 18 * HOUR);
    }

    @Test
    void resumeAfterFirstDeadlineDoesNotStartAnotherDelay() {
        insertTask(MarketingTaskStatus.PENDING, true, 1, MarketingNewGroupDelayUnit.HOUR);
        long resumedAt = 19 * HOUR;
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.startPendingTask(TASK_ID, START_AT)).isEqualTo(1);
            assertThat(mapper.pauseSendingTask(TASK_ID, START_AT + 10 * MINUTE)).isEqualTo(1);
            assertThat(mapper.resumePausedTask(TASK_ID, resumedAt)).isEqualTo(1);
        });

        assertTiming(START_AT, resumedAt);
    }

    @Test
    void disabledDelayKeepsManualStartScheduledStartAndResumeImmediatelyDue() {
        insertTask(MarketingTaskStatus.PENDING, false, 1, MarketingNewGroupDelayUnit.HOUR);
        transaction.executeWithoutResult(status ->
                assertThat(mapper.startPendingTask(TASK_ID, START_AT)).isEqualTo(1));
        assertTiming(START_AT, START_AT);

        transaction.executeWithoutResult(status -> {
            assertThat(mapper.pauseSendingTask(TASK_ID, START_AT + MINUTE)).isEqualTo(1);
            assertThat(mapper.resumePausedTask(TASK_ID, START_AT + 2 * MINUTE)).isEqualTo(1);
        });
        assertTiming(START_AT, START_AT + 2 * MINUTE);

        jdbc.update("UPDATE marketing_task SET status = ?, started_at = NULL, next_round_at = NULL WHERE id = ?",
                MarketingTaskStatus.PENDING.code(), TASK_ID);
        transaction.executeWithoutResult(status ->
                assertThat(mapper.startDueWaitingTask(TASK_ID, START_AT)).isEqualTo(1));
        assertTiming(START_AT, START_AT);
    }

    @Test
    void anotherTenantCannotStartResumeOrLockTask() {
        insertTask(MarketingTaskStatus.PENDING, true, 10, MarketingNewGroupDelayUnit.MINUTE);
        TenantContext.set(8L);
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.startPendingTask(TASK_ID, START_AT)).isZero();
            assertThat(mapper.startDueWaitingTask(TASK_ID, START_AT)).isZero();
            assertThat(mapper.selectTaskByIdForUpdate(TASK_ID)).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM marketing_task WHERE id = ?", Integer.class, TASK_ID))
                .isEqualTo(MarketingTaskStatus.PENDING.code());

        jdbc.update("UPDATE marketing_task SET status = ?, started_at = ? WHERE id = ?",
                MarketingTaskStatus.PAUSED.code(), START_AT, TASK_ID);
        transaction.executeWithoutResult(status ->
                assertThat(mapper.resumePausedTask(TASK_ID, START_AT + MINUTE)).isZero());
        assertThat(jdbc.queryForObject("SELECT status FROM marketing_task WHERE id = ?", Integer.class, TASK_ID))
                .isEqualTo(MarketingTaskStatus.PAUSED.code());
    }

    @Test
    void newGroupWaitsItsOwnFullDelayThenJoinsLaterOrdinaryRounds() {
        insertTask(MarketingTaskStatus.SENDING, true, 1, MarketingNewGroupDelayUnit.HOUR);
        createCurrentGroupSchema();
        MarketingTaskSendAttempt waiting = waitingAttempt();
        transaction.executeWithoutResult(status -> assertThat(mapper.insertSendAttempt(waiting)).isEqualTo(1));

        assertThat(mapper.selectDueWaitingNewGroupAttempts(18 * HOUR, 10)).isEmpty();
        assertThat(mapper.selectDynamicTargetGroups(TARGET_ID, ACCOUNT_ID, null, 18 * HOUR)).isEmpty();
        long firstSendAt = 18 * HOUR + 30 * MINUTE;
        assertThat(mapper.selectDueWaitingNewGroupAttempts(firstSendAt, 10))
                .extracting(MarketingTaskSendAttempt::getId).containsExactly(waiting.getId());
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.markWaitingAttemptSubmitted(waiting.getId(), "first-send", firstSendAt)).isEqualTo(1);
            assertThat(mapper.markWaitingAttemptSubmitted(waiting.getId(), "duplicate", firstSendAt)).isZero();
        });

        assertThat(mapper.selectDynamicTargetGroups(TARGET_ID, ACCOUNT_ID, null, firstSendAt)).isEmpty();
        assertThat(mapper.selectDynamicTargetGroups(TARGET_ID, ACCOUNT_ID, null, 19 * HOUR))
                .extracting(MarketingTargetCandidateRow::getGroupJid).containsExactly("new-group@g.us");
    }

    @Test
    void taskLockMakesOrdinaryGroupSelectionWaitForNewGroupRegistration() throws Exception {
        insertTask(MarketingTaskStatus.SENDING, true, 1, MarketingNewGroupDelayUnit.HOUR);
        createCurrentGroupSchema();
        assertThat(mapper.selectDynamicTargetGroups(TARGET_ID, ACCOUNT_ID, null, 18 * HOUR)).hasSize(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ordinaryStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var registration = executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    transaction.executeWithoutResult(status -> {
                        assertThat(mapper.selectTaskByIdForUpdate(TASK_ID)).isNotNull();
                        locked.countDown();
                        awaitRelease(release);
                        assertThat(mapper.insertSendAttempt(waitingAttempt())).isEqualTo(1);
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var ordinary = executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    return transaction.execute(status -> {
                        ordinaryStarted.countDown();
                        assertThat(mapper.selectTaskByIdForUpdate(TASK_ID)).isNotNull();
                        return mapper.selectDynamicTargetGroups(TARGET_ID, ACCOUNT_ID, null, 18 * HOUR);
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(ordinaryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> ordinary.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            registration.get(5, TimeUnit.SECONDS);
            assertThat(ordinary.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void insertTask(MarketingTaskStatus status, boolean delayed, int value, MarketingNewGroupDelayUnit unit) {
        jdbc.update("""
                INSERT INTO marketing_task (id, tenant_id, business_type, status, task_start_at,
                  is_new_group_delay_enabled, new_group_delay_value, new_group_delay_unit, current_round_no)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, 0)
                """, TASK_ID, TENANT_ID, status.code(), START_AT, delayed, value, unit.code());
    }

    private void assertTiming(long startedAt, long nextRoundAt) {
        MarketingTask stored = mapper.selectTaskById(TASK_ID);
        assertThat(stored.getStatus()).isEqualTo(MarketingTaskStatus.SENDING.code());
        assertThat(stored.getStartedAt()).isEqualTo(startedAt);
        assertThat(stored.getNextRoundAt()).isEqualTo(nextRoundAt);
    }

    private MarketingTaskSendAttempt waitingAttempt() {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(TASK_ID);
        attempt.setTargetId(TARGET_ID);
        attempt.setGroupJid("new-group@g.us");
        attempt.setRoundNo(0L);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setStatus(MarketingSendAttemptStatus.WAITING.code());
        attempt.setDetectedAt(START_AT + 30 * MINUTE);
        attempt.setScheduledSendAt(18 * HOUR + 30 * MINUTE);
        attempt.setAttemptedAt(attempt.getDetectedAt());
        attempt.setCreatedAt(attempt.getDetectedAt());
        return attempt;
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("营销首发测试行锁等待超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void createTaskSchema() {
        jdbc.execute("""
                CREATE TABLE marketing_task (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, task_name VARCHAR(128), business_type TINYINT,
                  account_group_id BIGINT, account_group_name VARCHAR(100), marketing_template_id BIGINT,
                  marketing_template_name VARCHAR(128), status TINYINT, selected_account_count INT,
                  target_group_count INT, target_pair_count INT, sent_message_count INT, failed_message_count INT,
                  send_per_round INT, account_group_send_interval_ms INT, send_interval_seconds INT,
                  is_online_check_enabled TINYINT, is_abnormal_group_skipped TINYINT,
                  is_auto_retry_enabled TINYINT, retry_limit INT, is_new_group_delay_enabled TINYINT,
                  new_group_delay_value INT, new_group_delay_unit TINYINT, current_round_no BIGINT,
                  remark VARCHAR(512), account_group_send_at BIGINT, task_start_at BIGINT, task_end_at BIGINT,
                  started_at BIGINT, next_round_at BIGINT, last_round_started_at BIGINT, last_sent_at BIGINT,
                  finished_at BIGINT, created_by BIGINT, created_at BIGINT, updated_at BIGINT, deleted_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE marketing_task_send_attempt (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, marketing_task_id BIGINT,
                  target_id BIGINT, group_link_id BIGINT, group_jid VARCHAR(128), group_name VARCHAR(128),
                  round_no BIGINT, attempt_no INT, is_retry TINYINT, command_id VARCHAR(64), status TINYINT,
                  reason_code VARCHAR(64), reason_message VARCHAR(255), message_id VARCHAR(128),
                  group_status VARCHAR(32), group_status_reason VARCHAR(64), group_status_checked_at BIGINT,
                  detected_at BIGINT, scheduled_send_at BIGINT, outbox_accepted_at BIGINT, submitted_at BIGINT,
                  result_at BIGINT, attempted_at BIGINT, created_at BIGINT,
                  UNIQUE (tenant_id, target_id, round_no, group_jid))
                """);
    }

    private void createCurrentGroupSchema() {
        jdbc.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, tenant_id BIGINT, ws_phone VARCHAR(32), "
                + "protocol_account_id VARCHAR(64), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_account_group_binding (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "account_id BIGINT, group_id BIGINT, participant_id BIGINT, "
                + "membership_active_since_at BIGINT, last_observed_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group (id BIGINT PRIMARY KEY, tenant_id BIGINT, group_jid VARCHAR(128))");
        jdbc.execute("CREATE TABLE wa_group_participant (id BIGINT PRIMARY KEY, tenant_id BIGINT, group_id BIGINT, "
                + "presence_status TINYINT, last_exit_type VARCHAR(32), presence_observed_at BIGINT)");
        jdbc.execute("CREATE TABLE group_link (id BIGINT PRIMARY KEY, tenant_id BIGINT, group_id BIGINT, "
                + "link_url VARCHAR(128), group_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE wa_group_profile (tenant_id BIGINT, group_id BIGINT, subject VARCHAR(128))");
        jdbc.execute("INSERT INTO account VALUES (501, 7, 'test-account', 'protocol-test-account', NULL)");
        jdbc.execute("INSERT INTO wa_account_group_binding VALUES (101, 7, 501, 1001, 3001, 1000, 1000)");
        jdbc.execute("INSERT INTO wa_group VALUES (1001, 7, 'new-group@g.us')");
        jdbc.execute("INSERT INTO wa_group_participant VALUES (3001, 7, 1001, 1, NULL, 1000)");
        jdbc.execute("INSERT INTO group_link VALUES (2001, 7, 1001, 'wa://group/new-group', 'new group')");
    }
}
