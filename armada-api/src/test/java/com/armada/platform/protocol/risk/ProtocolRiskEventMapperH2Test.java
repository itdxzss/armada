package com.armada.platform.protocol.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.risk.mapper.ProtocolRiskEventMapper;
import com.armada.platform.protocol.risk.model.ProtocolRiskEvent;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用真实 Mapper XML 和 H2 MySQL 模式验证协议风控事件只追加、幂等和租户隔离。 */
@SpringJUnitConfig(ProtocolRiskEventMapperH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class ProtocolRiskEventMapperH2Test {

    @Autowired private DataSource dataSource;
    @Autowired private ProtocolRiskEventMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE protocol_risk_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    event_id VARCHAR(191) NOT NULL,
                    signal_code VARCHAR(64) NOT NULL,
                    scope_type VARCHAR(16) NOT NULL,
                    operation_type VARCHAR(64),
                    account_id BIGINT,
                    protocol_account_id VARCHAR(191),
                    protocol_backend VARCHAR(32),
                    source VARCHAR(64) NOT NULL,
                    business_type VARCHAR(64),
                    business_id BIGINT,
                    business_item_id BIGINT,
                    group_business_id BIGINT,
                    command_id VARCHAR(191),
                    message_id VARCHAR(191),
                    target_kind VARCHAR(16),
                    chat_jid VARCHAR(191),
                    raw_code VARCHAR(64),
                    reason_message VARCHAR(255),
                    is_active TINYINT,
                    enforcement_type VARCHAR(64),
                    restricted_until BIGINT,
                    trace_id VARCHAR(64),
                    worker_id VARCHAR(128),
                    occurred_at BIGINT NOT NULL,
                    received_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, event_id)
                )
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void duplicateDeliveryDoesNotOverwriteTheFirstFactAndTenantsAreIndependent()
            throws SQLException {
        ProtocolRiskEvent first = event(7L, "evt-1", "RATE_LIMITED", "first");
        assertThat(mapper.insertIdempotent(first)).isEqualTo(1);
        assertThat(mapper.insertIdempotent(event(7L, "evt-1", "RATE_LIMITED", "changed")))
                .isZero();

        TenantContext.set(8L);
        assertThat(mapper.insertIdempotent(event(8L, "evt-1", "CHAT_SUSPENDED", "other")))
                .isEqualTo(1);

        assertThat(queryLong("SELECT COUNT(*) FROM protocol_risk_event")).isEqualTo(2);
        assertThat(queryText("SELECT reason_message FROM protocol_risk_event "
                + "WHERE tenant_id=7 AND event_id='evt-1'")).isEqualTo("first");
    }

    private static ProtocolRiskEvent event(
            long tenantId, String eventId, String code, String reason) {
        ProtocolRiskEvent row = new ProtocolRiskEvent();
        row.setTenantId(tenantId);
        row.setEventId(eventId);
        row.setSignalCode(code);
        row.setScopeType("OPERATION");
        row.setSource("message.send_result_reported");
        row.setReasonMessage(reason);
        row.setOccurredAt(1_000L);
        row.setReceivedAt(2_000L);
        return row;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryText(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:protocol_risk_event_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/platform/protocol/ProtocolRiskEventMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ProtocolRiskEventMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(ProtocolRiskEventMapper.class);
        }
    }
}
