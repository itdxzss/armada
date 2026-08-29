package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.service.HyperlinkRecipientStateMachine;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 专有生成列/JSON 迁移的结构门禁。 */
@SpringJUnitConfig(HyperlinkTaskLifecycleMigrationSqlTest.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskLifecycleMigrationSqlTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTaskRecipientMapper recipientMapper;

    @Autowired
    private HyperlinkTaskAccountUsageMapper usageMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        resetRecipientSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void migrationCreatesTenTaskTablesAndFrozenUniqueness() throws IOException {
        String sql = resource("/db/migration/V157__hyperlink_task_lifecycle.sql");

        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS hyperlink_task (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_content (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_runtime (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_recipient (",
                "CREATE TABLE IF NOT EXISTS hyperlink_billing_reservation (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_round (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_account_usage (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_round_account (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_recipient_claim (",
                "CREATE TABLE IF NOT EXISTS hyperlink_task_account_stat (");
        assertThat(sql).contains(
                "uq_hyperlink_recipient",
                "uq_hyperlink_recipient_command",
                "uq_hyperlink_recipient_ack",
                "uq_hyperlink_round_active",
                "uq_hyperlink_billing_task",
                "idx_hyperlink_round_start_global\n"
                        + "        (round_status, scheduled_at, id, tenant_id, hyperlink_task_id)",
                "idx_hyperlink_round_lifecycle_global\n"
                        + "        (round_status, next_dispatch_at, scheduled_at, updated_at, id, tenant_id, hyperlink_task_id)",
                "idx_hyperlink_round_dispatch_global\n"
                        + "        (round_status, next_dispatch_at, id, tenant_id, hyperlink_task_id)",
                "idx_hyperlink_recipient_projection_global\n"
                        + "        (needs_metrics_projection, tenant_id, hyperlink_task_id, updated_at, id)",
                "idx_hyperlink_recipient_reconciliation_global\n"
                        + "        (send_status, next_dispatch_at, id, tenant_id, hyperlink_task_id)",
                "idx_hyperlink_recipient_account_sending\n"
                        + "        (tenant_id, account_id, send_status, id)",
                "idx_hyperlink_runtime_completion_global\n"
                        + "        (is_enabled, run_status, provision_status, updated_at, hyperlink_task_id, tenant_id)",
                "idx_hyperlink_recipient_claim_provision_global\n"
                        + "        (claim_status, lease_expires_at, updated_at, id, tenant_id, hyperlink_task_id)",
                "idx_hyperlink_recipient_claim_cleanup_global\n"
                        + "        (claim_status, updated_at, id, tenant_id, hyperlink_task_id)",
                "column_name = 'retention_class'",
                "retention_class TINYINT GENERATED ALWAYS AS "
                        + "(CASE WHEN aggregate_type = ''HYPERLINK_TASK_RECIPIENT'' THEN 1 ELSE 0 END) STORED",
                "idx_protocol_outbox_retention_class "
                        + "(status, retention_class, created_at, id)",
                "DROP INDEX idx_protocol_outbox_retention_aggregate",
                "information_schema.statistics",
                "claimed_by_hyperlink_task_id",
                "MODIFY COLUMN title VARCHAR(1024)");
        assertThat(count(sql, "CREATE TABLE IF NOT EXISTS hyperlink_"))
                .as("V157 只创建用户确认的 10 张任务表")
                .isEqualTo(10);
        assertThat(sql).doesNotContain(
                "hyperlink_task_recipient_round",
                "hyperlink_delivery_attempt",
                "hyperlink_task_ban",
                "hyperlink_click",
                "hyperlink_task_click_bucket_30m");
        assertThat(sql).contains(
                "failure_code INT DEFAULT NULL COMMENT '准备失败稳定业务码'",
                "failure_reason VARCHAR(255) DEFAULT NULL COMMENT '准备失败脱敏摘要'",
                "protocol_id_snapshot VARCHAR(32) NOT NULL COMMENT '协议标识快照'",
                "protocol_account_id_snapshot VARCHAR(128) NOT NULL COMMENT '协议账号句柄快照'",
                "protocol_backend TINYINT NOT NULL COMMENT '协议后端:1WEB 2ANDROID'");
    }

    @Test
    void recipientMapperKeepsCommandedFactsAndMonotonicallyAppliesAck() throws SQLException {
        insertRecipient(1, null, 1, 0);
        insertRecipient(2, "hl:7:11:2", 2, 0);
        insertRecipient(3, "hl:7:11:3", 5, 2);

        var stopCandidates = recipientMapper.lockUnsubmittedForStop(7L, 11L, 21L, 2, 500);
        assertThat(stopCandidates).extracting(HyperlinkTaskRecipient::getId).containsExactly(1L);
        assertThat(recipientMapper.stopUnsubmittedByIds(11L,
                stopCandidates.stream().map(HyperlinkTaskRecipient::getId).toList(), 1_000L))
                .isEqualTo(1);
        assertThat(value("SELECT send_status FROM hyperlink_task_recipient WHERE id=1"))
                .isEqualTo("6");
        assertThat(value("SELECT send_status FROM hyperlink_task_recipient WHERE id=2"))
                .isEqualTo("2");
        assertThat(value("SELECT click_count FROM hyperlink_task_recipient WHERE id=3"))
                .isEqualTo("2");

        HyperlinkTaskRecipient ack = new HyperlinkTaskRecipient();
        ack.setId(2L);
        ack.setSendStatus(5);
        ack.setProtocolMessageId("m-2");
        ack.setUpdatedAt(2_000L);
        assertThat(recipientMapper.advanceAck(ack, 2)).isEqualTo(1);
        assertThat(value("SELECT send_status || ':' || protocol_message_id || ':' || submitted_at"
                + " FROM hyperlink_task_recipient WHERE id=2")).isEqualTo("5:m-2:2000");

        assertThat(recipientMapper.scheduleReconciliation("hl:7:11:2", 32_000L, 2_100L))
                .isZero();
        assertThat(value("SELECT send_status FROM hyperlink_task_recipient WHERE id=2"))
                .isEqualTo("5");
    }

    @Test
    void recipientMapperLetsTheDatabaseRejectDuplicateShortCodes() throws SQLException {
        insertRecipient(1, null, 1, 0);
        insertRecipient(2, null, 1, 0);
        HyperlinkTaskRecipient first = assignedRecipient(1L, "hl:7:11:1", "AbCdEf0123_-xyZ9");
        HyperlinkTaskRecipient collision = assignedRecipient(2L, "hl:7:11:2", "AbCdEf0123_-xyZ9");

        assertThat(recipientMapper.assignCommand(first)).isEqualTo(1);
        assertThatThrownBy(() -> recipientMapper.assignCommand(collision))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(value("SELECT send_status FROM hyperlink_task_recipient WHERE id=2"))
                .isEqualTo("1");
    }

    @Test
    void projectionBatchUsesTheGlobalIndexOrderAndHonorsItsLimit() throws SQLException {
        insertRecipient(11, null, 3, 0);
        insertRecipient(12, null, 3, 0);
        insertRecipient(13, null, 3, 0);
        insertRecipient(14, null, 3, 0);
        execute("UPDATE hyperlink_task_recipient SET needs_metrics_projection=1, "
                + "hyperlink_task_id=11, updated_at=300 WHERE id=11");
        execute("UPDATE hyperlink_task_recipient SET needs_metrics_projection=1, "
                + "hyperlink_task_id=12, updated_at=100 WHERE id=12");
        execute("UPDATE hyperlink_task_recipient SET needs_metrics_projection=1, "
                + "hyperlink_task_id=11, updated_at=200 WHERE id=13");
        execute("UPDATE hyperlink_task_recipient SET needs_metrics_projection=1, "
                + "tenant_id=8, hyperlink_task_id=14, updated_at=50 WHERE id=14");

        assertThat(recipientMapper.selectMetricsProjectionCandidates(2))
                .extracting(HyperlinkTaskRecipient::getId)
                .containsExactly(13L, 11L);
    }

    private HyperlinkTaskRecipient assignedRecipient(long id, String commandId, String shortCode) {
        HyperlinkTaskRecipient row = new HyperlinkTaskRecipient();
        row.setId(id);
        row.setHyperlinkTaskRoundId(31L);
        row.setRoundNo(1L);
        row.setAccountId(41L + id);
        row.setSenderPhoneSnapshot("861390000000" + id);
        row.setSenderAccountTypeSnapshot(1);
        row.setProtocolId("web");
        row.setProtocolBackend(1);
        row.setCommandId(commandId);
        row.setShortCode(shortCode);
        row.setNextDispatchAt(2_000L);
        row.setUpdatedAt(1_000L);
        return row;
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    @Test
    void concurrentAckCompareAndSetHasExactlyOneWinner() throws Exception {
        insertRecipient(4, "hl:7:11:4", 2, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> advanceSuccessAfter(start, "m-4-a"));
            Future<Integer> second = executor.submit(() -> advanceSuccessAfter(start, "m-4-b"));
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(value("SELECT send_status FROM hyperlink_task_recipient WHERE id=4"))
                    .isEqualTo("3");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usageThenRecipientLockSeesSuccessCommittedWhileAckWasWaiting() throws Exception {
        insertRecipient(5, "hl:7:11:5", HyperlinkRecipientStatus.SENDING.code(), 0);
        insertUsageAndAssignRecipient(5);
        CountDownLatch usageLocked = new CountDownLatch(1);
        CountDownLatch ackReadSending = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> success = executor.submit(() -> inTransaction(() -> {
                usageMapper.selectByTaskAndAccountForUpdate(11L, 17L);
                usageLocked.countDown();
                await(ackReadSending, "等待 ACK 读到旧 SENDING 超时");
                HyperlinkTaskRecipient result = result(5L, HyperlinkRecipientStatus.SUCCESS,
                        "message-5", 2_000L);
                assertThat(recipientMapper.applyResult(result)).isEqualTo(1);
                assertThat(usageMapper.completeSlot(41L, true, 2_000L)).isEqualTo(1);
            }));
            assertThat(usageLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> ack = executor.submit(() -> inTransaction(() -> {
                HyperlinkTaskRecipient observed = recipientMapper.selectByCommandId("hl:7:11:5");
                assertThat(observed.getSendStatus()).isEqualTo(HyperlinkRecipientStatus.SENDING.code());
                ackReadSending.countDown();
                usageMapper.selectByTaskAndAccountForUpdate(11L, 17L);
                HyperlinkTaskRecipient latest = recipientMapper.selectByIdentityForUpdate(
                        7L, 11L, 5L, "hl:7:11:5");
                assertThat(latest.getSendStatus()).isEqualTo(HyperlinkRecipientStatus.SUCCESS.code());
                HyperlinkRecipientStatus next = new HyperlinkRecipientStateMachine().advance(
                        HyperlinkRecipientStatus.fromCode(latest.getSendStatus()),
                        HyperlinkRecipientStatus.READ);
                latest.setSendStatus(next.code());
                latest.setProtocolMessageId("message-5");
                latest.setUpdatedAt(3_000L);
                assertThat(recipientMapper.advanceAck(
                        latest, HyperlinkRecipientStatus.SUCCESS.code())).isEqualTo(1);
            }));

            success.get(5, TimeUnit.SECONDS);
            ack.get(5, TimeUnit.SECONDS);
            assertThat(value("SELECT send_status || ':' || successful_send_count || ':'"
                    + " || in_flight_count FROM hyperlink_task_recipient r"
                    + " JOIN hyperlink_task_account_usage u ON u.hyperlink_task_id=r.hyperlink_task_id"
                    + " WHERE r.id=5")).isEqualTo("5:1:0");
        } finally {
            executor.shutdownNow();
        }
    }

    private int advanceSuccessAfter(CountDownLatch start, String messageId) throws Exception {
        TenantContext.set(7L);
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发 ACK 开始超时");
            }
            HyperlinkTaskRecipient ack = new HyperlinkTaskRecipient();
            ack.setId(4L);
            ack.setSendStatus(3);
            ack.setProtocolMessageId(messageId);
            ack.setUpdatedAt(2_000L);
            return recipientMapper.advanceAck(ack, 2);
        } finally {
            TenantContext.clear();
        }
    }

    private HyperlinkTaskRecipient result(long id, HyperlinkRecipientStatus status,
            String messageId, long now) {
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        recipient.setId(id);
        recipient.setCommandId("hl:7:11:" + id);
        recipient.setSendStatus(status.code());
        recipient.setProtocolMessageId(messageId);
        recipient.setUpdatedAt(now);
        return recipient;
    }

    private void insertUsageAndAssignRecipient(long recipientId) throws SQLException {
        execute("UPDATE hyperlink_task_recipient SET account_id=17 WHERE id=" + recipientId,
                "INSERT INTO hyperlink_task_account_usage "
                        + "(id,tenant_id,hyperlink_task_id,account_id,success_limit,"
                        + "successful_send_count,reserved_success_slot_count,in_flight_count,"
                        + "usage_status,version,created_at,updated_at) "
                        + "VALUES (41,7,11,17,100,0,1,1,1,1,100,100)");
    }

    private void inTransaction(Runnable action) {
        TenantContext.set(7L);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
        } finally {
            TenantContext.clear();
        }
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发锁序被中断", exception);
        }
    }

    private static String resource(String path) throws IOException {
        try (var input = HyperlinkTaskLifecycleMigrationSqlTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void insertRecipient(long id, String commandId, int status, int clickCount)
            throws SQLException {
        String command = commandId == null ? "NULL" : "'" + commandId + "'";
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,data_package_id,data_package_generation,"
                + "source_import_id,recipient_phone_snapshot,"
                + "command_id,send_status,next_dispatch_at,metrics_projected_status,click_count,"
                + "created_at,updated_at) VALUES (" + id + ",7,11,21,2,21,'861380000000" + id
                + "'," + command + "," + status + ",0," + status + "," + clickCount
                + ",100,100)");
    }

    private String value(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void resetRecipientSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  data_package_id BIGINT,
                  data_package_generation INT,
                  source_import_id BIGINT NOT NULL,
                  recipient_phone_snapshot VARCHAR(32) NOT NULL,
                  recipient_country_iso2_snapshot CHAR(2),
                  hyperlink_task_round_id BIGINT,
                  round_no BIGINT,
                  account_id BIGINT,
                  sender_phone_snapshot VARCHAR(32),
                  sender_country_iso2_snapshot CHAR(2),
                  sender_account_type_snapshot TINYINT,
                  protocol_id VARCHAR(32),
                  protocol_backend TINYINT,
                  command_id VARCHAR(64),
                  protocol_message_id VARCHAR(128),
                  short_code VARCHAR(24),
                  send_status TINYINT NOT NULL,
                  next_dispatch_at BIGINT NOT NULL,
                  metrics_projected_status TINYINT NOT NULL,
                  needs_metrics_projection TINYINT,
                  fail_code VARCHAR(64),
                  fail_reason VARCHAR(255),
                  submitted_at BIGINT,
                  sent_at BIGINT,
                  delivered_at BIGINT,
                  read_at BIGINT,
                  failed_at BIGINT,
                  click_count INT NOT NULL,
                  metrics_projected_at BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE(tenant_id,hyperlink_task_id,recipient_phone_snapshot),
                  UNIQUE(tenant_id,command_id),
                  UNIQUE(tenant_id,account_id,protocol_id,protocol_message_id),
                  UNIQUE(short_code)
                )
                """, """
                CREATE TABLE hyperlink_task_account_usage (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  success_limit INT NOT NULL,
                  successful_send_count BIGINT NOT NULL,
                  reserved_success_slot_count INT NOT NULL,
                  in_flight_count INT NOT NULL,
                  usage_status INT NOT NULL,
                  invalid_code VARCHAR(64),
                  invalid_reason VARCHAR(255),
                  invalid_at BIGINT,
                  version INT NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL
                )
                """);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_task_lifecycle_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            Resource[] locations = {
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountUsageMapper.xml")
            };
            factory.setMapperLocations(locations);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientMapper.class);
        }

        @Bean
        HyperlinkTaskAccountUsageMapper usageMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskAccountUsageMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
