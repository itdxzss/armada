package com.armada.platform.protocol.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 协议命令 Outbox 追踪列的 H2 MySQL 模式真实 Mapper 测试。 */
@SpringJUnitConfig(ProtocolCommandOutboxMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class ProtocolCommandOutboxMapperInMemoryTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProtocolCommandOutboxMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void batchInsertAndDispatchSelectRoundTripTraceId() {
        ProtocolCommandOutbox row = pendingRow();

        assertThat(mapper.batchInsertPending(List.of(row))).isEqualTo(1);

        assertThat(mapper.selectDispatchable(
                ProtocolCommandOutboxStatus.PENDING.code(), 100L, 10))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getTenantId()).isEqualTo(7L);
                    assertThat(found.getCommandId()).isEqualTo("cmd-trace-1");
                    assertThat(found.getTraceId()).isEqualTo(FIXED_TRACE_ID);
                });
    }

    @Test
    void deleteSentBeforeRemovesOnlyExpiredSentRows() throws SQLException {
        insertRow("cmd-sent-old", ProtocolCommandOutboxStatus.SENT.code(), 100L);
        insertRow("cmd-sent-fresh", ProtocolCommandOutboxStatus.SENT.code(), 900L);
        insertRow("cmd-dead-old", ProtocolCommandOutboxStatus.DEAD.code(), 100L);
        insertRow("cmd-canceled-old", ProtocolCommandOutboxStatus.CANCELED.code(), 100L);
        insertRow("cmd-pending-old", ProtocolCommandOutboxStatus.PENDING.code(), 100L);

        // 只清已发送且超过保留期的行；死信与已取消量小且有诊断价值，必须保留。
        assertThat(mapper.deleteRegularSentBefore(500L, 10)).isEqualTo(1);
        assertThat(remainingCommandIds())
                .containsExactlyInAnyOrder(
                        "cmd-sent-fresh", "cmd-dead-old", "cmd-canceled-old", "cmd-pending-old");
    }

    @Test
    void deleteSentBeforeStopsAtBatchLimitSoTheRunCanDrainInBoundedBatches() throws SQLException {
        insertRow("cmd-sent-1", ProtocolCommandOutboxStatus.SENT.code(), 100L);
        insertRow("cmd-sent-2", ProtocolCommandOutboxStatus.SENT.code(), 101L);
        insertRow("cmd-sent-3", ProtocolCommandOutboxStatus.SENT.code(), 102L);

        // 单批有上限，调用方据此判断是否还要继续删下一批。
        assertThat(mapper.deleteRegularSentBefore(500L, 2)).isEqualTo(2);
        assertThat(mapper.deleteRegularSentBefore(500L, 2)).isEqualTo(1);
        assertThat(mapper.deleteRegularSentBefore(500L, 2)).isZero();
    }

    @Test
    void hyperlinkSentRowsKeepThirtyDaysAndRemainReplayableOnTheOriginalRow()
            throws SQLException {
        long day = 24L * 60 * 60 * 1_000;
        long now = 40L * day;
        insertRow("cmd-normal-day8", ProtocolCommandOutboxStatus.SENT.code(),
                now - 8L * day);
        insertHyperlinkRow("cmd-hyperlink-day8", now - 8L * day);
        insertHyperlinkRow("cmd-hyperlink-day29", now - 29L * day);
        insertHyperlinkRow("cmd-hyperlink-day30-boundary", now - 30L * day);
        insertHyperlinkRow("cmd-hyperlink-expired", now - 30L * day - 1);

        assertThat(value("SELECT retention_class FROM protocol_command_outbox "
                + "WHERE command_id='cmd-normal-day8'"))
                .isEqualTo(Integer.toString(ProtocolCommandOutboxMapper.REGULAR_RETENTION_CLASS));
        assertThat(value("SELECT retention_class FROM protocol_command_outbox "
                + "WHERE command_id='cmd-hyperlink-day29'"))
                .isEqualTo(Integer.toString(ProtocolCommandOutboxMapper.HYPERLINK_RETENTION_CLASS));
        assertThat(mapper.deleteRegularSentBefore(now - 7L * day, 10)).isEqualTo(1);
        assertThat(mapper.deleteHyperlinkSentBefore(now - 30L * day, 10)).isEqualTo(1);
        assertThat(remainingCommandIds()).containsExactlyInAnyOrder(
                "cmd-hyperlink-day8", "cmd-hyperlink-day29",
                "cmd-hyperlink-day30-boundary");

        String originalId = value("SELECT id FROM protocol_command_outbox "
                + "WHERE command_id='cmd-hyperlink-day29'");
        assertThat(mapper.replayMessageCommand(7L, "cmd-hyperlink-day29",
                "message.send.requested",
                List.of(ProtocolCommandOutboxStatus.SENT.code()),
                ProtocolCommandOutboxStatus.PENDING.code(), now)).isEqualTo(1);
        assertThat(value("SELECT id FROM protocol_command_outbox "
                + "WHERE command_id='cmd-hyperlink-day29'"))
                .isEqualTo(originalId);
        assertThat(value("SELECT status FROM protocol_command_outbox "
                + "WHERE command_id='cmd-hyperlink-day29'"))
                .isEqualTo(Integer.toString(ProtocolCommandOutboxStatus.PENDING.code()));
    }

    private void insertRow(String commandId, int status, long createdAt) throws SQLException {
        ProtocolCommandOutbox row = pendingRow();
        row.setCommandId(commandId);
        mapper.batchInsertPending(List.of(row));
        execute("UPDATE protocol_command_outbox SET status = " + status
                + ", created_at = " + createdAt + " WHERE command_id = '" + commandId + "'");
    }

    private void insertHyperlinkRow(String commandId, long createdAt) throws SQLException {
        ProtocolCommandOutbox row = pendingRow();
        row.setCommandId(commandId);
        row.setCommandType("message.send.requested");
        row.setAggregateType("HYPERLINK_TASK_RECIPIENT");
        mapper.batchInsertPending(List.of(row));
        execute("UPDATE protocol_command_outbox SET status = "
                + ProtocolCommandOutboxStatus.SENT.code() + ", created_at = " + createdAt
                + " WHERE command_id = '" + commandId + "'");
    }

    private String value(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private List<String> remainingCommandIds() throws SQLException {
        List<String> ids = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT command_id FROM protocol_command_outbox ORDER BY command_id")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static ProtocolCommandOutbox pendingRow() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId("cmd-trace-1");
        row.setCommandType("account.online.requested");
        row.setAggregateType("ACCOUNT");
        row.setAggregateId(101L);
        row.setKafkaTopic("protocol.account.commands.v1");
        row.setKafkaKey("acc-101");
        row.setProtocolAccountId("acc-101");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("{}");
        row.setTraceId(FIXED_TRACE_ID);
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(0L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE protocol_command_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    command_id VARCHAR(64) NOT NULL UNIQUE,
                    batch_id VARCHAR(64),
                    command_type VARCHAR(64) NOT NULL,
                    aggregate_type VARCHAR(32) NOT NULL,
                    retention_class TINYINT GENERATED ALWAYS AS
                      (CASE WHEN aggregate_type='HYPERLINK_TASK_RECIPIENT' THEN 1 ELSE 0 END),
                    aggregate_id BIGINT NOT NULL,
                    kafka_topic VARCHAR(128) NOT NULL,
                    kafka_key VARCHAR(128) NOT NULL,
                    protocol_account_id VARCHAR(128) NOT NULL,
                    protocol_backend VARCHAR(16) NOT NULL,
                    payload_json JSON NOT NULL,
                    trace_id VARCHAR(32),
                    status TINYINT NOT NULL,
                    retry_count INT NOT NULL,
                    next_retry_at BIGINT NOT NULL,
                    locked_by VARCHAR(64),
                    locked_at BIGINT,
                    sent_at BIGINT,
                    last_error VARCHAR(1024),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("CREATE INDEX idx_protocol_outbox_retention_class ON protocol_command_outbox "
                + "(status, retention_class, created_at, id)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:protocol_outbox_trace_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/platform/protocol/ProtocolCommandOutboxMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ProtocolCommandOutboxMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(ProtocolCommandOutboxMapper.class);
        }
    }
}
