package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingTaskQuery;
import com.armada.marketing.model.dto.MarketingTaskQuery;
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

/** 使用生产 Mapper XML 验证普通营销和拉群营销任务根的用户/租户隔离。 */
@SpringJUnitConfig(MarketingTaskUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class MarketingTaskUserDataScopeMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private MarketingTaskMapper taskMapper;
    @Autowired
    private GroupPullMarketingMapper groupPullMapper;

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
    void selfScopeReturnsOnlyOwnOrdinaryAndGroupPullTasks() {
        assertThat(countOrdinary(DataScope.self(USER_ONE_ID))).isEqualTo(1);
        assertThat(countOrdinary(DataScope.self(USER_TWO_ID))).isEqualTo(1);
        assertThat(countGroupPull(DataScope.self(USER_ONE_ID))).isEqualTo(1);
        assertThat(countGroupPull(DataScope.self(USER_TWO_ID))).isEqualTo(1);

        assertThat(taskMapper.selectTaskByIdForScope(101L, DataScope.self(USER_ONE_ID)))
                .isNotNull();
        assertThat(taskMapper.selectTaskByIdForScope(102L, DataScope.self(USER_ONE_ID)))
                .isNull();
        assertThat(groupPullMapper.selectTaskForUpdateForScope(
                201L, DataScope.self(USER_ONE_ID)))
                .isNotNull();
        assertThat(groupPullMapper.selectTaskForUpdateForScope(
                202L, DataScope.self(USER_ONE_ID)))
                .isNull();
    }

    @Test
    void allScopeIncludesHistoricalNullButNeverCrossesTenant() {
        assertThat(countOrdinary(DataScope.all(9001L))).isEqualTo(3);
        assertThat(countGroupPull(DataScope.all(9001L))).isEqualTo(3);
        assertThat(taskMapper.selectTaskByIdForScope(103L, DataScope.all(9001L)))
                .isNotNull();
        assertThat(groupPullMapper.selectTaskForUpdateForScope(203L, DataScope.all(9001L)))
                .isNotNull();
        assertThat(taskMapper.selectTaskByIdForScope(105L, DataScope.all(9001L)))
                .isNull();

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(countOrdinary(DataScope.all(9001L))).isEqualTo(1);
            assertThat(countGroupPull(DataScope.all(9001L))).isEqualTo(1);
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosedAcrossBothTaskKinds() {
        assertThat(countOrdinary(null)).isZero();
        assertThat(countOrdinary(DataScope.system("marketing maintenance"))).isZero();
        assertThat(countGroupPull(null)).isZero();
        assertThat(countGroupPull(DataScope.system("marketing maintenance"))).isZero();
        assertThat(taskMapper.selectTaskByIdForScope(101L, null)).isNull();
        assertThat(groupPullMapper.selectTaskForUpdateForScope(
                201L, DataScope.system("marketing maintenance")))
                .isNull();
    }

    private long countOrdinary(DataScope scope) {
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.applyDataScope(scope);
        return taskMapper.countPage(query);
    }

    private long countGroupPull(DataScope scope) {
        GroupPullMarketingTaskQuery query = new GroupPullMarketingTaskQuery();
        query.applyDataScope(scope);
        return groupPullMapper.countTasks(query);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE marketing_task (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  task_name VARCHAR(128) NOT NULL,
                  business_type INT NOT NULL,
                  account_group_id BIGINT,
                  account_group_name VARCHAR(128),
                  marketing_template_id BIGINT,
                  marketing_template_name VARCHAR(128),
                  status INT NOT NULL,
                  selected_account_count INT,
                  target_group_count INT,
                  target_pair_count INT,
                  sent_message_count INT,
                  failed_message_count INT,
                  send_per_round INT,
                  account_group_send_interval_ms BIGINT,
                  send_interval_seconds INT,
                  is_online_check_enabled BOOLEAN,
                  is_abnormal_group_skipped BOOLEAN,
                  is_auto_retry_enabled BOOLEAN,
                  retry_limit INT,
                  is_new_group_delay_enabled BOOLEAN,
                  new_group_delay_value INT,
                  new_group_delay_unit INT,
                  current_round_no INT,
                  remark VARCHAR(255),
                  account_group_send_at BIGINT,
                  task_start_at BIGINT,
                  task_end_at BIGINT,
                  started_at BIGINT,
                  next_round_at BIGINT,
                  last_round_started_at BIGINT,
                  last_sent_at BIGINT,
                  finished_at BIGINT,
                  created_by BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_pull_marketing_task (
                  marketing_task_id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  block_reason INT,
                  resource_status INT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO marketing_task
                  (id, tenant_id, owner_user_id, task_name, business_type, status,
                   created_at, updated_at, deleted_at)
                VALUES
                  (101, 7, 1001, 'ordinary-u1', 1, 7, 1, 1, NULL),
                  (102, 7, 1002, 'ordinary-u2', 1, 7, 1, 1, NULL),
                  (103, 7, NULL, 'ordinary-history', 1, 7, 1, 1, NULL),
                  (104, 7, 1001, 'ordinary-deleted', 1, 7, 1, 1, 99),
                  (105, 8, 1001, 'ordinary-other-tenant', 1, 7, 1, 1, NULL),
                  (201, 7, 1001, 'pull-u1', 2, 7, 1, 1, NULL),
                  (202, 7, 1002, 'pull-u2', 2, 7, 1, 1, NULL),
                  (203, 7, NULL, 'pull-history', 2, 7, 1, 1, NULL),
                  (204, 7, 1001, 'pull-deleted', 2, 7, 1, 1, 99),
                  (205, 8, 1001, 'pull-other-tenant', 2, 7, 1, 1, NULL)
                """, """
                INSERT INTO group_pull_marketing_task
                  (marketing_task_id, tenant_id, block_reason, resource_status)
                VALUES
                  (201, 7, 0, 1),
                  (202, 7, 0, 1),
                  (203, 7, 0, 1),
                  (204, 7, 0, 1),
                  (205, 8, 0, 1)
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

    /** 只加载本切片的生产 XML 和生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:marketing_task_user_scope_mapper_test;"
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
                    new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"),
                    new ClassPathResource("mapper/marketing/GroupPullMarketingMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        MarketingTaskMapper marketingTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTaskMapper.class);
        }

        @Bean
        GroupPullMarketingMapper groupPullMarketingMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupPullMarketingMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
