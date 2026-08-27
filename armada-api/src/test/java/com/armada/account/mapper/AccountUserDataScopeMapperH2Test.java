package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.vo.AccountStatsVoRow;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.security.DataScope;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/** 账号域三个权限根使用真实 Mapper XML 执行用户范围和租户范围隔离。 */
@SpringJUnitConfig(AccountUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountUserDataScopeMapperH2Test {

    private static final long CURRENT_TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountGroupMapper accountGroupMapper;

    @Autowired
    private AccountImportBatchMapper accountImportBatchMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(CURRENT_TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfScopeReturnsOnlyTheActorRowsForBothUsers() {
        assertThat(queryCounts(DataScope.self(USER_ONE_ID)))
                .isEqualTo(new RootCounts(1, 1, 1));
        assertThat(queryCounts(DataScope.self(USER_TWO_ID)))
                .isEqualTo(new RootCounts(2, 2, 2));
    }

    @Test
    void allScopeIncludesBothUsersAndHistoricalNullOnlyInsideCurrentTenant() {
        assertThat(queryCounts(DataScope.all(USER_ONE_ID)))
                .isEqualTo(new RootCounts(6, 6, 6));

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(queryCounts(DataScope.all(USER_ONE_ID)))
                    .isEqualTo(new RootCounts(1, 1, 1));
        } finally {
            TenantContext.set(CURRENT_TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosedAcrossAllThreeRoots() {
        assertThat(queryCounts(null)).isEqualTo(new RootCounts(0, 0, 0));
        assertThat(queryCounts(DataScope.system("account owner reconciliation")))
                .isEqualTo(new RootCounts(0, 0, 0));
    }

    @Test
    void protocolPhoneLookupUsesTheSameSelfAllAndFailClosedRules() {
        assertThat(accountMapper.selectActiveByWsPhonesForScope(
                java.util.List.of("phone-u1", "phone-u2", "phone-legacy"),
                DataScope.self(USER_ONE_ID)))
                .extracting(com.armada.account.model.entity.Account::getId)
                .containsExactly(101L);
        assertThat(accountMapper.selectActiveByWsPhonesForScope(
                java.util.List.of("phone-u1", "phone-u2", "phone-legacy"),
                DataScope.all(USER_ONE_ID)))
                .extracting(com.armada.account.model.entity.Account::getId)
                .containsExactlyInAnyOrder(101L, 102L, 103L);
        assertThat(accountMapper.selectActiveByWsPhonesForScope(
                java.util.List.of("phone-u1"), DataScope.system("test"))).isEmpty();
        assertThat(accountMapper.selectActiveByWsPhonesForScope(
                java.util.List.of("phone-u1"), null)).isEmpty();
    }

    @Test
    void protocolIdLookupsAndOnlineFilteringUseTheSameScopeRules() {
        var ids = java.util.List.of(101L, 102L, 103L);

        assertThat(accountMapper.selectActiveByIdForScope(102L, DataScope.self(USER_ONE_ID)))
                .isNull();
        assertThat(accountMapper.selectActiveByIdForScope(102L, DataScope.self(USER_TWO_ID)))
                .extracting(com.armada.account.model.entity.Account::getId)
                .isEqualTo(102L);
        assertThat(accountMapper.selectActiveByIdsForScope(ids, DataScope.self(USER_ONE_ID)))
                .extracting(com.armada.account.model.entity.Account::getId)
                .containsExactly(101L);
        assertThat(accountMapper.selectActiveByIdsForScope(ids, DataScope.all(USER_ONE_ID)))
                .extracting(com.armada.account.model.entity.Account::getId)
                .containsExactlyInAnyOrder(101L, 102L, 103L);
        assertThat(accountMapper.selectOnlineAccountIdsByIdsForScope(
                ids, 1, DataScope.self(USER_ONE_ID))).containsExactly(101L);

        assertThat(accountMapper.selectActiveByIdsForScope(ids, null)).isEmpty();
        assertThat(accountMapper.selectOnlineAccountIdsByIdsForScope(
                ids, 1, DataScope.system("test"))).isEmpty();
    }

    private RootCounts queryCounts(DataScope dataScope) {
        AccountStatsVoRow accountStats = accountMapper.statsSummary(dataScope);

        AccountGroupQuery groupQuery = new AccountGroupQuery();
        groupQuery.applyDataScope(dataScope);

        AccountImportQuery importQuery = new AccountImportQuery();
        importQuery.applyDataScope(dataScope);

        return new RootCounts(
                accountStats.getTotal(),
                accountGroupMapper.countPage(groupQuery),
                accountImportBatchMapper.countPage(importQuery));
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  ws_phone VARCHAR(32),
                  protocol_id VARCHAR(32),
                  protocol_account_id VARCHAR(64),
                  dispatched_at BIGINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_state (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  account_state INT,
                  login_state INT,
                  risk_status INT,
                  mute_status INT
                )
                """, """
                CREATE TABLE account_group (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_import_batch (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO account
                  (id, tenant_id, owner_user_id, dispatched_at, deleted_at)
                VALUES
                  (101, 7, 1001, NULL, NULL),
                  (102, 7, 1002, NULL, NULL),
                  (103, 7, NULL, NULL, NULL),
                  (104, 7, 1001, NULL, 900),
                  (105, 8, 1001, NULL, NULL),
                  (106, 7, 1002, NULL, NULL),
                  (107, 7, NULL, NULL, NULL),
                  (108, 7, NULL, NULL, NULL)
                """, """
                UPDATE account SET ws_phone = CASE id
                  WHEN 101 THEN 'phone-u1'
                  WHEN 102 THEN 'phone-u2'
                  WHEN 103 THEN 'phone-legacy'
                  WHEN 105 THEN 'phone-other-tenant'
                  ELSE CONCAT('phone-', id) END,
                  protocol_id = 'WEB',
                  protocol_account_id = CONCAT('acc-', id)
                """, """
                INSERT INTO account_state
                  (id, tenant_id, account_id, account_state, login_state, risk_status)
                VALUES
                  (201, 7, 101, 2, 1, 1),
                  (202, 7, 102, 2, 2, 1),
                  (203, 7, 103, 3, 2, 1),
                  (204, 8, 105, 2, 1, 1)
                """, """
                INSERT INTO account_group (id, tenant_id, owner_user_id, deleted_at)
                VALUES
                  (301, 7, 1001, NULL),
                  (302, 7, 1002, NULL),
                  (303, 7, NULL, NULL),
                  (304, 7, 1001, 900),
                  (305, 8, 1001, NULL),
                  (306, 7, 1002, NULL),
                  (307, 7, NULL, NULL),
                  (308, 7, NULL, NULL)
                """, """
                INSERT INTO account_import_batch (id, tenant_id, owner_user_id, deleted_at)
                VALUES
                  (401, 7, 1001, NULL),
                  (402, 7, 1002, NULL),
                  (403, 7, NULL, NULL),
                  (404, 7, 1001, 900),
                  (405, 8, 1001, NULL),
                  (406, 7, 1002, NULL),
                  (407, 7, NULL, NULL),
                  (408, 7, NULL, NULL)
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

    private record RootCounts(long accounts, long accountGroups, long importBatches) {
    }

    /** 加载三个生产 Mapper XML、生产租户插件和测试事务管理器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:account_user_data_scope_mapper_test;"
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
                    new ClassPathResource("mapper/account/AccountGroupMapper.xml"),
                    new ClassPathResource("mapper/account/AccountImportBatchMapper.xml"));
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
        AccountGroupMapper accountGroupMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMapper.class);
        }

        @Bean
        AccountImportBatchMapper accountImportBatchMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountImportBatchMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
