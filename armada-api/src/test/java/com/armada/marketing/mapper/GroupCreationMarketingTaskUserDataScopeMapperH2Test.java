package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
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

/** 使用生产 Mapper XML 验证建群营销任务根的用户/租户隔离。 */
@SpringJUnitConfig(GroupCreationMarketingTaskUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupCreationMarketingTaskUserDataScopeMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private GroupCreationMarketingTaskMapper mapper;

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
    void selfScopeReturnsOnlyOwnTasksAndBatchSelection() {
        assertThat(count(DataScope.self(USER_ONE_ID))).isEqualTo(1);
        assertThat(count(DataScope.self(USER_TWO_ID))).isEqualTo(1);
        assertThat(mapper.selectTaskByIdForScope(101L, DataScope.self(USER_ONE_ID)))
                .isNotNull();
        assertThat(mapper.selectTaskByIdForScope(102L, DataScope.self(USER_ONE_ID)))
                .isNull();
        assertThat(mapper.selectTasksByIdsForScope(
                java.util.List.of(101L, 102L), DataScope.self(USER_ONE_ID)))
                .extracting("id")
                .containsExactly(101L);
    }

    @Test
    void allScopeIncludesHistoricalNullButNeverCrossesTenant() {
        assertThat(count(DataScope.all(9001L))).isEqualTo(3);
        assertThat(mapper.selectTaskByIdForScope(103L, DataScope.all(9001L)))
                .isNotNull();
        assertThat(mapper.selectTaskByIdForScope(105L, DataScope.all(9001L)))
                .isNull();

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(count(DataScope.all(9001L))).isEqualTo(1);
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosed() {
        assertThat(count(null)).isZero();
        assertThat(count(DataScope.system("group creation maintenance"))).isZero();
        assertThat(mapper.selectTaskByIdForScope(101L, null)).isNull();
        assertThat(mapper.selectTasksByIdsForScope(
                java.util.List.of(101L), DataScope.system("group creation maintenance")))
                .isEmpty();
    }

    private long count(DataScope scope) {
        GroupCreationMarketingTaskQuery query = new GroupCreationMarketingTaskQuery();
        query.applyDataScope(scope);
        return mapper.countPage(query);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE group_creation_marketing_task (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  task_name VARCHAR(128) NOT NULL,
                  account_group_id BIGINT,
                  account_group_name VARCHAR(128),
                  marketing_template_id BIGINT,
                  marketing_template_name VARCHAR(128),
                  marketing_task_id BIGINT,
                  status INT NOT NULL,
                  matched_item_count INT,
                  unmatched_file_count INT,
                  success_count INT,
                  failed_count INT,
                  abandoned_count INT,
                  send_interval_seconds INT,
                  group_name_prefix VARCHAR(100),
                  remark VARCHAR(512),
                  created_by BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  finished_at BIGINT,
                  deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO group_creation_marketing_task
                  (id, tenant_id, owner_user_id, task_name, status, created_at, updated_at, deleted_at)
                VALUES
                  (101, 7, 1001, 'u1', 1, 1, 1, NULL),
                  (102, 7, 1002, 'u2', 1, 1, 1, NULL),
                  (103, 7, NULL, 'history', 1, 1, 1, NULL),
                  (104, 7, 1001, 'deleted', 1, 1, 1, 99),
                  (105, 8, 1001, 'other-tenant', 1, 1, 1, NULL)
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
            dataSource.setURL("jdbc:h2:mem:gcm_task_user_scope_mapper_test;"
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
                    new ClassPathResource("mapper/marketing/GroupCreationMarketingTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        GroupCreationMarketingTaskMapper groupCreationMarketingTaskMapper(
                SqlSessionTemplate template) {
            return template.getMapper(GroupCreationMarketingTaskMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
