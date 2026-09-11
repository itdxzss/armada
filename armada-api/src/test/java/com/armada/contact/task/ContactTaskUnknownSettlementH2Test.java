package com.armada.contact.task;

import com.armada.boot.config.MyBatisConfig;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.scheduler.ContactTaskLifecycleWorker;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Mapper、租户插件及事务验证未知结果排干与完成后迟到回执的统计一致性。 */
class ContactTaskUnknownSettlementH2Test {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ContactFriendTaskMapper tasks;
    private ContactFriendTaskAccountMapper accounts;
    private ContactFriendTaskRecipientMapper recipients;
    private ContactTaskLifecycleWorker lifecycle;
    private ContactTaskSendResultSink sink;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(7L);
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V163__contact_friend_task.sql"));
        }
        // MySQL PREPARE 迁移不受 H2 支持，仅在测试库补齐相同的运行时列。
        jdbc.execute("ALTER TABLE contact_friend_task ADD current_round_no BIGINT DEFAULT 0");
        jdbc.execute("ALTER TABLE contact_friend_task_account ADD stop_reason VARCHAR(255)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ALTER COLUMN contact_phone DROP NOT NULL");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD round_no BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD command_id VARCHAR(64)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD delivered_at BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD read_at BIGINT");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        MyBatisConfig mybatis = new MyBatisConfig();
        factory.setPlugins(mybatis.mybatisPlusInterceptor(mybatis.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/contact/ContactFriendTaskMapper.xml"),
                new ClassPathResource("mapper/contact/ContactFriendTaskAccountMapper.xml"),
                new ClassPathResource("mapper/contact/ContactFriendTaskRecipientMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(ContactFriendTaskMapper.class);
        accounts = session.getMapper(ContactFriendTaskAccountMapper.class);
        recipients = session.getMapper(ContactFriendTaskRecipientMapper.class);
        lifecycle = new ContactTaskLifecycleWorker(tasks, accounts, recipients,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
        sink = new ContactTaskSendResultSink(tasks, accounts, recipients, () -> 1100L);
        jdbc.update("""
                INSERT INTO contact_friend_task(id, tenant_id, name, message_type, content, created_at,
                  updated_at, run_status, next_round_at, used_account_count)
                VALUES(1,7,'task',1,'hello',1,1,1,900,3)
                """);
        jdbc.update("""
                INSERT INTO contact_friend_task_account(id, tenant_id, task_id, account_id, state,
                  account_status_snapshot, created_at, updated_at)
                VALUES(10,7,1,20,'RUNNING','valid',1,1)
                """);
        insertRecipient(100, 7, 10, "UNKNOWN");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void insertRecipient(long id, long tenant, long account, String state) {
        jdbc.update("""
                INSERT INTO contact_friend_task_recipient(id, tenant_id, task_id, task_account_id,
                  contact_jid, send_status, command_id, protocol_message_id, attempt_count, created_at, updated_at)
                VALUES(?,?,1,?,?,?,?,?,1,1,1)
                """, id, tenant, account, id + "@lid", state, "cmd-" + id, "msg-" + id);
    }

    private void complete() {
        tx.executeWithoutResult(status -> lifecycle.completeDrainedTask(7L, 1L));
    }

    @ParameterizedTest
    @CsvSource({"PENDING,valid", "RUNNING,invalid", "RUNNING,"})
    void unknownAndFailedRecipientsFinishWithoutInventingAccountFailure(String state, String snapshot) {
        jdbc.update("UPDATE contact_friend_task_account SET state=?, account_status_snapshot=?, fail_num=1 WHERE id=10",
                state, snapshot);
        insertRecipient(101, 7, 10, "FAILED");

        complete();

        assertThat(accounts.selectById(10L).getState()).isEqualTo("DONE");
        assertThat(accounts.selectById(10L).getAccountStatusSnapshot()).isEqualTo(snapshot);
        assertThat(accounts.selectById(10L).getFailNum()).isOne();
        assertThat(recipients.selectById(100L).getSendStatus()).isEqualTo("UNKNOWN");
        assertThat(recipients.selectById(100L).getAttemptCount()).isOne();
        assertThat(recipients.claimForSend(100L, 2L, "retry", 1200L)).isZero();
        assertThat(tasks.selectById(1L).getRunStatus()).isEqualTo(2);
        assertThat(tasks.selectById(1L).getInvalidAccountNum()).isZero();
        assertThat(tasks.selectById(1L).getSuccessMessageNum()).isZero();
    }

    @Test
    void definiteFailuresWithoutUnknownKeepExistingFailedSettlement() {
        jdbc.update("UPDATE contact_friend_task_recipient SET send_status='FAILED' WHERE id=100");
        complete();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("FAILED");
        assertThat(accounts.selectById(10L).getAccountStatusSnapshot()).isEqualTo("invalid");
        assertThat(tasks.selectById(1L).getInvalidAccountNum()).isOne();
    }

    @Test
    void explicitlyStoppedAccountIsNotReopenedByUnknownOrLateAck() {
        tx.executeWithoutResult(status -> accounts.stopAccount(10L, "ACCOUNT_OFFLINE", 900L));
        complete();
        tx.executeWithoutResult(status -> sink.handleAck(ack("DELIVERED")));

        assertThat(accounts.selectById(10L).getState()).isEqualTo("FAILED");
        assertThat(accounts.selectById(10L).getStopReason()).isEqualTo("ACCOUNT_OFFLINE");
        assertThat(accounts.selectById(10L).getAccountStatusSnapshot()).isEqualTo("valid");
        assertThat(tasks.selectById(1L).getInvalidAccountNum()).isOne();
        assertThat(tasks.selectById(1L).getRunStatus()).isEqualTo(2);
        assertThat(tasks.selectById(1L).getNextRoundAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SENDING"})
    void unfinishedRecipientStillPreventsAccountAndTaskSettlement(String state) {
        insertRecipient(101, 7, 10, state);
        complete();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("RUNNING");
        assertThat(tasks.selectById(1L).getRunStatus()).isOne();
        assertThat(tasks.selectById(1L).getNextRoundAt()).isEqualTo(900L);
    }

    @Test
    void foreignUnknownCannotChangeOwnSettlementAndForeignRowsStayUntouched() {
        jdbc.update("UPDATE contact_friend_task_recipient SET send_status='FAILED' WHERE id=100");
        insertRecipient(101, 8, 10, "UNKNOWN");
        jdbc.update("""
                INSERT INTO contact_friend_task_account(id, tenant_id, task_id, account_id, state,
                  account_status_snapshot, created_at, updated_at)
                VALUES(11,8,1,21,'RUNNING','valid',1,1)
                """);
        insertRecipient(102, 8, 11, "UNKNOWN");
        complete();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT state FROM contact_friend_task_account WHERE id=11", String.class))
                .isEqualTo("RUNNING");
        TenantContext.set(8L);
        assertThat(tasks.incrementSuccessMessageNum(1L, 1, 1200L)).isZero();
        TenantContext.set(7L);
        assertThat(tasks.selectById(1L).getSuccessMessageNum()).isZero();
    }

    @Test
    void lateAckUpdatesRoundedAverageOnceWithoutReopeningCompletedTask() {
        complete();
        tx.executeWithoutResult(status -> {
            sink.handleAck(ack("DELIVERED"));
            sink.handleAck(ack("READ"));
            sink.handleAck(ack("DELIVERED"));
        });
        assertThat(recipients.selectById(100L).getSendStatus()).isEqualTo("SUCCESS");
        assertThat(accounts.selectById(10L).getSentNum()).isOne();
        assertThat(tasks.selectById(1L).getSuccessMessageNum()).isOne();
        assertThat(tasks.selectById(1L).getAvgSendPerAccount()).isEqualByComparingTo("0.33");
        assertThat(accounts.selectById(10L).getState()).isEqualTo("DONE");
        assertThat(tasks.selectById(1L).getInvalidAccountNum()).isZero();
        assertThat(tasks.selectById(1L).getRunStatus()).isEqualTo(2);
        assertThat(tasks.selectById(1L).getNextRoundAt()).isNull();
    }

    @Test
    void successDeltaUsesPreviousCountAndZeroDenominatorIsSafe() {
        jdbc.update("UPDATE contact_friend_task SET success_message_num=4, avg_send_per_account=1.33 WHERE id=1");
        tx.executeWithoutResult(status -> tasks.incrementSuccessMessageNum(1L, 2, 1100L));
        assertThat(tasks.selectById(1L).getSuccessMessageNum()).isEqualTo(6);
        assertThat(tasks.selectById(1L).getAvgSendPerAccount()).isEqualByComparingTo("2.00");
        jdbc.update("UPDATE contact_friend_task SET used_account_count=0 WHERE id=1");
        tx.executeWithoutResult(status -> tasks.incrementSuccessMessageNum(1L, 1, 1200L));
        assertThat(tasks.selectById(1L).getSuccessMessageNum()).isEqualTo(7);
        assertThat(tasks.selectById(1L).getAvgSendPerAccount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private ProtocolMessageAckEvent ack(String status) {
        return new ProtocolMessageAckEvent("ack", 7L, "contact_task", null, null, "cmd-100",
                20L, "android", "account", "100@lid", "PRIVATE", "msg-100", status,
                true, null, null, 1100L, "worker");
    }
}
