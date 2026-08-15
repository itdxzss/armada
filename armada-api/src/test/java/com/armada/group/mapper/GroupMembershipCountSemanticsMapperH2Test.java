package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountQuery;
import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.mapper.MarketingTaskMapper;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号列表与营销账号树群数量口径的 H2 Mapper XML 回归测试。 */
@SpringJUnitConfig(GroupMembershipCountSemanticsMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupMembershipCountSemanticsMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private MarketingTaskMapper marketingTaskMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void accountListAndMarketingTreeKeepDifferentMembershipCountSemantics() {
        AccountQuery accountQuery = new AccountQuery();
        accountQuery.setPhone("923300000501");
        accountQuery.setPage(1);
        accountQuery.setPageSize(10);

        assertThat(accountMapper.selectPage(accountQuery))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupsNum()).isEqualTo(3));
        assertThat(marketingTaskMapper.selectAccountTreeAccounts(11L))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupCount()).isEqualTo(5));
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
                  account_type TINYINT NOT NULL, device_os TINYINT, number_source TINYINT,
                  channel_name VARCHAR(128), protocol_id VARCHAR(32), protocol_account_id VARCHAR(64),
                  group_baseline_state TINYINT NOT NULL, account_group_id BIGINT, ownership TINYINT NOT NULL,
                  lease_until BIGINT, dispatched_at BIGINT, created_at BIGINT NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  account_state TINYINT, login_state TINYINT, risk_status TINYINT, risk_end_time BIGINT,
                  cooldown_until BIGINT, mute_status TINYINT, block_error_code VARCHAR(32),
                  block_reason VARCHAR(255), state_source VARCHAR(64), truth_ip VARCHAR(45),
                  proxy_country VARCHAR(64), proxy_source VARCHAR(64), pull_into_group_count INT,
                  invalidated_at BIGINT, last_state_sync_time BIGINT
                )
                """, """
                CREATE TABLE account_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100),
                  marketing_occupancy_type VARCHAR(32), marketing_occupancy_task_id BIGINT,
                  marketing_locked_at BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_credential (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, cred_format TINYINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE ip_proxy (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  bound_account_id BIGINT, region VARCHAR(64), source VARCHAR(64),
                  status TINYINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE country (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_group_baseline (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, baseline_group_jids VARCHAR(1024)
                )
                """, """
                CREATE TABLE account_group_membership (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, membership_status TINYINT NOT NULL, deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute(
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) VALUES (11, 7, '当前租户组', NULL)",
                """
                INSERT INTO account
                  (id, tenant_id, ws_phone, account_type, ownership, protocol_account_id,
                   group_baseline_state, account_group_id, created_at, deleted_at)
                VALUES
                  (501, 7, '923300000501', 1, 1, 'acc_501', 3, 11, 100, NULL),
                  (601, 8, '923300000601', 1, 1, 'acc_601', 3, 11, 100, NULL)
                """,
                """
                INSERT INTO account_group_membership
                  (id, tenant_id, account_id, group_jid, membership_status, deleted_at)
                VALUES
                  (1, 7, 501, 'in-group@g.us', 1, NULL),
                  (2, 7, 501, 'unconfirmed@g.us', 2, NULL),
                  (3, 7, 501, 'kicked@g.us', 3, NULL),
                  (4, 7, 501, 'left@g.us', 4, NULL),
                  (5, 7, 501, 'not-in-group@g.us', 5, NULL),
                  (6, 7, 501, 'deleted@g.us', 1, 999),
                  (7, 7, 501, '   ', 1, NULL),
                  (8, 8, 601, 'other-tenant@g.us', 1, NULL)
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

    /** 本测试只加载两份真实 Mapper XML，并启用生产租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_membership_count_semantics;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/account/AccountMapper.xml"),
                    new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountMapper.class);
        }

        @Bean
        MarketingTaskMapper marketingTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTaskMapper.class);
        }
    }
}
