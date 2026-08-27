package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行历史群拉人真实 Mapper XML 的用户范围查询。 */
@SpringJUnitConfig(HistoricalGroupPullExecutionUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HistoricalGroupPullExecutionUserDataScopeMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HistoricalGroupPullExecutionMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS", """
                CREATE TABLE historical_group_pull_execution (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  created_by BIGINT,
                  idempotency_key VARCHAR(128) NOT NULL,
                  operation_account_id BIGINT NOT NULL,
                  source_account_group_id BIGINT,
                  group_jid VARCHAR(128) NOT NULL,
                  group_subject_snapshot VARCHAR(255),
                  invite_link VARCHAR(512),
                  puller_account_group_id BIGINT NOT NULL,
                  puller_account_id BIGINT,
                  single_add_count INT NOT NULL,
                  marketing_template_id BIGINT,
                  normal_count INT NOT NULL DEFAULT 0,
                  marketing_count INT NOT NULL DEFAULT 0,
                  invalid_count INT NOT NULL DEFAULT 0,
                  duplicate_count INT NOT NULL DEFAULT 0,
                  pull_success_count INT NOT NULL DEFAULT 0,
                  pull_failure_count INT NOT NULL DEFAULT 0,
                  send_success_count INT NOT NULL DEFAULT 0,
                  send_failure_count INT NOT NULL DEFAULT 0,
                  pull_status TINYINT NOT NULL DEFAULT 0,
                  marketing_status TINYINT NOT NULL DEFAULT 0,
                  failure_stage VARCHAR(64),
                  error_code VARCHAR(64),
                  error_message CLOB,
                  started_at BIGINT,
                  finished_at BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE (tenant_id, owner_user_id, idempotency_key)
                )
                """, """
                INSERT INTO historical_group_pull_execution
                  (id, tenant_id, owner_user_id, created_by, idempotency_key,
                   operation_account_id, source_account_group_id, group_jid,
                   puller_account_group_id, single_add_count, pull_status,
                   marketing_status, created_at, updated_at)
                VALUES
                  (101, 7, 1001, 1001, 'same-key', 11, 21, 'group@g.us', 31, 5, 0, 0, 100, 100),
                  (102, 7, 1002, 1002, 'same-key', 12, 21, 'group@g.us', 32, 5, 0, 0, 200, 200),
                  (103, 7, NULL, NULL, 'legacy-key', 13, 21, 'group@g.us', 33, 5, 0, 0, 300, 300),
                  (201, 8, 1001, 1001, 'other-tenant', 14, 21, 'group@g.us', 34, 5, 0, 0, 400, 400)
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfAdminHistoricalAndTenantBoundariesFailClosed() {
        DataScope u1 = DataScope.self(1001L);
        DataScope admin = DataScope.all(9001L);

        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 101L, u1)).isNotNull();
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 102L, u1)).isNull();
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 103L, u1)).isNull();
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 102L, admin)).isNotNull();
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 103L, admin)).isNotNull();
        assertThat(mapper.selectByTenantAndIdForScope(
                TENANT_ID, 101L, DataScope.system("test"))).isNull();
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 101L, null)).isNull();

        TenantContext.set(8L);
        assertThat(mapper.selectByTenantAndIdForScope(TENANT_ID, 101L, admin)).isNull();
    }

    @Test
    void idempotencyAndLatestQueriesStayInsideOwnerScope() {
        assertThat(mapper.selectByTenantOwnerAndIdempotencyKey(
                TENANT_ID, 1001L, "same-key").getId()).isEqualTo(101L);
        assertThat(mapper.selectByTenantOwnerAndIdempotencyKey(
                TENANT_ID, 1002L, "same-key").getId()).isEqualTo(102L);

        assertThat(mapper.selectLatestByTenantSourceGroupAndGroupForScope(
                TENANT_ID, 21L, "group@g.us", DataScope.self(1001L)).getId())
                .isEqualTo(101L);
        assertThat(mapper.selectLatestByTenantSourceGroupAndGroupForScope(
                TENANT_ID, 21L, "group@g.us", DataScope.all(9001L)).getId())
                .isEqualTo(103L);
    }

    @Test
    void insertPersistsTrustedOwnerAndCreatedBy() {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setOwnerUserId(1001L);
        row.setCreatedBy(1001L);
        row.setIdempotencyKey("new-key");
        row.setOperationAccountId(15L);
        row.setSourceAccountGroupId(21L);
        row.setGroupJid("new@g.us");
        row.setPullerAccountGroupId(31L);
        row.setSingleAddCount(5);
        row.setPullStatus(0);
        row.setMarketingStatus(0);
        row.setCreatedAt(500L);
        row.setUpdatedAt(500L);

        assertThat(mapper.insert(row)).isEqualTo(1);
        assertThat(mapper.selectByTenantAndIdForScope(
                TENANT_ID, row.getId(), DataScope.self(1001L)))
                .satisfies(saved -> {
                    assertThat(saved.getOwnerUserId()).isEqualTo(1001L);
                    assertThat(saved.getCreatedBy()).isEqualTo(1001L);
                });
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:historical_pull_scope_mapper_test;"
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
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/HistoricalGroupPullExecutionMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        HistoricalGroupPullExecutionMapper historicalGroupPullExecutionMapper(
                SqlSessionTemplate template) {
            return template.getMapper(HistoricalGroupPullExecutionMapper.class);
        }
    }
}
