package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAuditEventMapper;
import com.armada.hyperlink.task.port.DatabaseHyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
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

/** 使用真实 Mapper XML 和 H2 MySQL 模式验证超链任务审计幂等与租户隔离。 */
@SpringJUnitConfig(HyperlinkTaskAuditMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskAuditMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTaskAuditEventMapper mapper;

    private DatabaseHyperlinkTaskAuditPort auditPort;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_task_audit_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    event_id VARCHAR(191) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    actor_user_id BIGINT,
                    hyperlink_task_id BIGINT NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, event_id)
                )
                """);
        TenantContext.set(7L);
        auditPort = new DatabaseHyperlinkTaskAuditPort(mapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recordIsIdempotentInsideTenantAndSameEventIdIsIndependentAcrossTenants()
            throws SQLException {
        HyperlinkTaskAuditPort.AuditEvent event = new HyperlinkTaskAuditPort.AuditEvent(
                "hyperlink-task:create:11", HyperlinkTaskAuditPort.Action.CREATE,
                7L, 8L, 11L, 1000L);

        auditPort.requireAvailable();
        auditPort.record(event);
        auditPort.record(event);

        TenantContext.set(8L);
        auditPort.record(new HyperlinkTaskAuditPort.AuditEvent(
                event.eventId(), event.action(), 8L, 9L, event.taskId(), event.occurredAt()));

        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_audit_event")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_audit_event "
                + "WHERE tenant_id=7 AND event_id='hyperlink-task:create:11'")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_audit_event "
                + "WHERE tenant_id=8 AND event_id='hyperlink-task:create:11'")).isEqualTo(1);
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

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:hyperlink_task_audit_test;MODE=MySQL;"
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
                    "mapper/hyperlink/task/HyperlinkTaskAuditEventMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskAuditEventMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskAuditEventMapper.class);
        }
    }
}
