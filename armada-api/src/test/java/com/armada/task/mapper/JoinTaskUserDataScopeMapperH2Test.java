package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.security.DataScope;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.JoinTaskQuery;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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

/** 使用生产 Mapper XML 验证进群任务根的用户/租户隔离。 */
@SpringJUnitConfig(JoinTaskUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class JoinTaskUserDataScopeMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired private DataSource dataSource;
    @Autowired private JoinTaskMapper mapper;

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
    void selfScopeReturnsOnlyOwnRootsIntervalsAndBatchSelection() {
        DataScope u1 = DataScope.self(USER_ONE_ID);
        assertThat(count(u1)).isEqualTo(1);
        assertThat(count(DataScope.self(USER_TWO_ID))).isEqualTo(1);
        assertThat(mapper.selectByTenantAndIdForScope(101L, u1)).isNotNull();
        assertThat(mapper.selectByTenantAndIdForScope(102L, u1)).isNull();
        assertThat(mapper.selectByIdsForScope(List.of(101L, 102L), u1))
                .extracting("id")
                .containsExactly(101L);
        assertThat(mapper.selectDistinctIntervals(u1)).containsExactly("5-10s");
    }

    @Test
    void allScopeIncludesHistoricalNullButNeverCrossesTenantOrDeletedRows() {
        DataScope admin = DataScope.all(9001L);
        assertThat(count(admin)).isEqualTo(3);
        assertThat(mapper.selectByTenantAndIdForScope(103L, admin)).isNotNull();
        assertThat(mapper.selectDistinctIntervals(admin))
                .containsExactly("10-20s", "20-30s", "5-10s");

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(count(admin)).isEqualTo(1);
            assertThat(mapper.selectByTenantAndIdForScope(101L, admin)).isNull();
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosed() {
        assertThat(count(null)).isZero();
        assertThat(count(DataScope.system("join task maintenance"))).isZero();
        assertThat(mapper.selectByTenantAndIdForScope(101L, null)).isNull();
        assertThat(mapper.selectByIdsForScope(
                List.of(101L), DataScope.system("join task maintenance"))).isEmpty();
        assertThat(mapper.selectDistinctIntervals(null)).isEmpty();
    }

    private long count(DataScope scope) {
        JoinTaskQuery query = new JoinTaskQuery();
        query.applyDataScope(scope);
        return mapper.countPage(query.toFilter());
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE join_task (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  name VARCHAR(128) NOT NULL,
                  account_group_ids VARCHAR(512),
                  links_text VARCHAR(512),
                  distribution_mode VARCHAR(32),
                  interval_label VARCHAR(64),
                  status VARCHAR(16),
                  created_at BIGINT NOT NULL,
                  deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO join_task
                  (id, tenant_id, owner_user_id, name, account_group_ids, links_text,
                   distribution_mode, interval_label, status, created_at, deleted_at)
                VALUES
                  (101, 7, 1001, 'u1', '[11]', 'u1-link', 'FIXED_ACCOUNTS_PER_LINK', '5-10s', 'DRAFT', 1, NULL),
                  (102, 7, 1002, 'u2', '[12]', 'u2-link', 'FIXED_ACCOUNTS_PER_LINK', '10-20s', 'DRAFT', 2, NULL),
                  (103, 7, NULL, 'history', '[13]', 'old-link', 'FIXED_ACCOUNT_MULTI_LINK', '20-30s', 'DONE', 3, NULL),
                  (104, 7, 1001, 'deleted', '[11]', 'deleted-link', 'FIXED_ACCOUNTS_PER_LINK', '40-50s', 'DRAFT', 4, 99),
                  (105, 8, 1001, 'other-tenant', '[11]', 'other-link', 'FIXED_ACCOUNTS_PER_LINK', '60-70s', 'DRAFT', 5, NULL)
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
            dataSource.setURL("jdbc:h2:mem:join_task_user_scope_mapper_test;"
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
            factory.setMapperLocations(new ClassPathResource("mapper/task/JoinTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        JoinTaskMapper joinTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(JoinTaskMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
