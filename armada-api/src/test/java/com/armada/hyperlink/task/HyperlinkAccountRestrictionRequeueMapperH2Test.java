package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号受限后，同一 recipient 原子释放为新账号可领取状态的真实 Mapper 测试。 */
@SpringJUnitConfig(HyperlinkAccountRestrictionRequeueMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkAccountRestrictionRequeueMapperH2Test {

    @Autowired private DataSource dataSource;
    @Autowired private HyperlinkTaskRecipientMapper mapper;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = new JdbcTemplate(dataSource);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  hyperlink_task_round_id BIGINT,
                  round_no BIGINT,
                  account_id BIGINT,
                  sender_phone_snapshot VARCHAR(32),
                  sender_country_iso2_snapshot VARCHAR(2),
                  sender_account_type_snapshot TINYINT,
                  sender_device_os_snapshot TINYINT,
                  protocol_id VARCHAR(32),
                  protocol_backend TINYINT,
                  command_id VARCHAR(64),
                  dispatch_attempt INT NOT NULL DEFAULT 1,
                  protocol_message_id VARCHAR(128),
                  short_code VARCHAR(24),
                  send_status TINYINT NOT NULL,
                  next_dispatch_at BIGINT NOT NULL,
                  metrics_projected_status TINYINT NOT NULL,
                  fail_code VARCHAR(64),
                  fail_reason VARCHAR(255),
                  submitted_at BIGINT,
                  failed_at BIGINT,
                  updated_at BIGINT NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO hyperlink_task_recipient (
                  id, tenant_id, hyperlink_task_id, hyperlink_task_round_id, round_no,
                  account_id, sender_phone_snapshot, sender_country_iso2_snapshot,
                  sender_account_type_snapshot, sender_device_os_snapshot,
                  protocol_id, protocol_backend, command_id, dispatch_attempt,
                  protocol_message_id, short_code, send_status, next_dispatch_at,
                  metrics_projected_status, fail_code, fail_reason, submitted_at,
                  failed_at, updated_at
                ) VALUES (
                  13, 7, 11, 31, 1, 17, 'sender', 'BR', 2, 1,
                  'web', 1, 'hl:7:11:13', 1, NULL, 'abc', 2, 3000,
                  2, NULL, NULL, 1000, NULL, 1000
                )
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void requeueClearsAccountAssignmentKeepsLogicalSendFactAndIncrementsAttempt() {
        assertThat(mapper.requeueAfterAccountRestriction(
                13L, "hl:7:11:13", 2_000L)).isEqualTo(1);

        assertThat(jdbc.queryForMap("""
                SELECT hyperlink_task_round_id, account_id, command_id,
                       dispatch_attempt, send_status, next_dispatch_at,
                       submitted_at, metrics_projected_status
                FROM hyperlink_task_recipient WHERE id=13
                """))
                .containsEntry("dispatch_attempt", 2)
                .containsEntry("send_status", 1)
                .containsEntry("next_dispatch_at", 2_000L)
                .containsEntry("submitted_at", 1_000L)
                .containsEntry("metrics_projected_status", 2)
                .containsEntry("hyperlink_task_round_id", null)
                .containsEntry("account_id", null)
                .containsEntry("command_id", null);
        assertThat(mapper.requeueAfterAccountRestriction(
                13L, "hl:7:11:13", 3_000L)).isZero();
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
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:hyperlink_requeue_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"));
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
    }
}
