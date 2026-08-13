package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.boot.config.MyBatisConfig;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号分组聚合统计的 H2 Mapper XML 回归测试。 */
@SpringJUnitConfig(AccountGroupMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountGroupMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountGroupMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE account_group (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  name VARCHAR(64) NOT NULL,
                  remark VARCHAR(255),
                  marketing_occupancy_type INT,
                  marketing_occupancy_task_id BIGINT,
                  marketing_locked_at BIGINT,
                  system_builtin TINYINT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_group_id BIGINT,
                  ws_phone VARCHAR(32),
                  protocol_account_id VARCHAR(128),
                  protocol_id VARCHAR(32),
                  deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE account_state (
                  account_id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  account_state INT,
                  login_state INT,
                  risk_status INT,
                  mute_status INT,
                  PRIMARY KEY (tenant_id, account_id)
                )
                """);
        execute("""
                INSERT INTO account_group
                  (id, tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (10, 7, '次管理员组', 0, 100, 100)
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selectPageCountsOnlyNormalOnlineAccountsWithCompleteSupportedProtocolIdentityAsExecutable()
            throws SQLException {
        insertAccount(1, "10001", "web-1", "WEB", 1, 1);
        insertAccount(2, "10002", "android-2", " android ", 1, 1);
        insertAccount(3, "10003", "unknown-3", "DESKTOP", 1, 1);
        insertAccount(4, "10004", "abnormal-4", "WEB", 3, 1);
        insertAccount(5, "10005", "offline-5", "ANDROID", 1, 2);
        insertAccount(6, "10006", " ", "WEB", 1, 1);

        AccountGroupQuery query = new AccountGroupQuery();
        query.setId(10L);
        query.setPageSize(10);

        AccountGroupVoRow row = mapper.selectPage(query).get(0);

        assertThat(row.getOnlineCount()).isEqualTo(5L);
        assertThat(row.getExecutableOnlineCount()).isEqualTo(2L);
    }

    private void insertAccount(
            long id,
            String wsPhone,
            String protocolAccountId,
            String protocolId,
            int accountState,
            int loginState) throws SQLException {
        execute("""
                INSERT INTO account
                  (id, tenant_id, account_group_id, ws_phone, protocol_account_id, protocol_id)
                VALUES (%d, 7, 10, '%s', '%s', '%s')
                """.formatted(id, wsPhone, protocolAccountId, protocolId));
        execute("""
                INSERT INTO account_state
                  (account_id, tenant_id, account_state, login_state, risk_status)
                VALUES (%d, 7, %d, %d, 1)
                """.formatted(id, accountState, loginState));
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
            h2.setURL("jdbc:h2:mem:account_group_mapper_test;MODE=MySQL;"
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
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountGroupMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountGroupMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMapper.class);
        }
    }
}
