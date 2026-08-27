package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
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

/** 群组列表批量刷新任务主表 Mapper H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupBatchTaskMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupBatchTaskMapperDbTest {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long OPERATOR_ID = 55L;
    private static final DataScope SELF = DataScope.self(OPERATOR_ID);
    private static final DataScope ALL = DataScope.all(99L);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupBatchTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void applyItemOutcomeIncrementsProgressPerItemAndOnlyCompletesOnTheFinalItem() {
        GroupBatchTask task = task("req-progress", 3);
        mapper.insert(task);

        mapper.applyItemOutcome(task.getId(), true, COMPLETED, RUNNING, 1_000L);
        GroupBatchTask afterFirst = mapper.selectById(task.getId(), SELF);
        assertThat(afterFirst.getSuccessCount()).isEqualTo(1);
        assertThat(afterFirst.getFailedCount()).isZero();
        // 进度必须在运行中就可读，否则前端轮询会一直停在 0% 直到任务结束。
        assertThat(afterFirst.getStatus()).isEqualTo(RUNNING);
        assertThat(afterFirst.getCompletedAt()).isNull();

        mapper.applyItemOutcome(task.getId(), false, COMPLETED, RUNNING, 2_000L);
        assertThat(mapper.selectById(task.getId(), SELF).getStatus()).isEqualTo(RUNNING);

        mapper.applyItemOutcome(task.getId(), true, COMPLETED, RUNNING, 3_000L);
        GroupBatchTask finished = mapper.selectById(task.getId(), SELF);
        assertThat(finished.getSuccessCount()).isEqualTo(2);
        assertThat(finished.getFailedCount()).isEqualTo(1);
        assertThat(finished.getStatus()).isEqualTo(COMPLETED);
        assertThat(finished.getCompletedAt()).isEqualTo(3_000L);
    }

    @Test
    void selectByRequestIdIsolatesTenantsSoIdempotencyCannotLeakAcrossThem() {
        mapper.insert(task("req-shared", 1));

        assertThat(mapper.selectByRequestId("req-shared", OPERATOR_ID)).isNotNull();
        assertThat(mapper.selectByRequestId("req-shared", OPERATOR_ID + 1)).isNull();
        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(mapper.selectByRequestId("req-shared", OPERATOR_ID)).isNull();
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    private static final int RUNNING = 2;
    private static final int COMPLETED = 3;
    private static final int CANCELED = 5;

    @Test
    void cancelIfRunnableOnlyTouchesTasksThatCanStillBeStopped() {
        GroupBatchTask task = task("req-cancel", 3);
        mapper.insert(task);

        assertThat(mapper.cancelIfRunnable(
                task.getId(), CANCELED, java.util.List.of(1, RUNNING), SELF, 9_000L)).isEqualTo(1);
        GroupBatchTask canceled = mapper.selectById(task.getId(), SELF);
        assertThat(canceled.getStatus()).isEqualTo(CANCELED);
        assertThat(canceled.getCompletedAt()).isEqualTo(9_000L);

        // 重复取消返回 0 行；已终结的任务也不会被改写成取消态。
        assertThat(mapper.cancelIfRunnable(
                task.getId(), CANCELED, java.util.List.of(1, RUNNING), SELF, 9_500L)).isZero();
        assertThat(mapper.selectById(task.getId(), SELF).getCompletedAt()).isEqualTo(9_000L);
    }

    @Test
    void selectStatusByIdReadsAcrossTenantsSoSchedulerThreadsCanSeeCancellation() {
        GroupBatchTask task = task("req-status", 1);
        mapper.insert(task);
        mapper.cancelIfRunnable(
                task.getId(), CANCELED, java.util.List.of(1, RUNNING), SELF, 9_000L);

        try {
            // 调度线程可能在别的租户上下文里，甚至取消来自另一个实例。
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(mapper.selectStatusById(task.getId())).isEqualTo(CANCELED);
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    @Test
    void directReadsEnforceSelfAllHistoricalAndTenantBoundaries() throws SQLException {
        GroupBatchTask userOne = task("req-user-one", 1);
        mapper.insert(userOne);
        GroupBatchTask userTwo = task("req-user-two", 1);
        userTwo.setOwnerUserId(OPERATOR_ID + 1);
        userTwo.setCreatedBy(OPERATOR_ID + 1);
        mapper.insert(userTwo);
        execute("""
                INSERT INTO group_batch_task
                  (id, tenant_id, owner_user_id, task_type, status, total_count,
                   success_count, failed_count, request_id, created_by, created_at)
                VALUES
                  (900, 7, NULL, 1, 1, 1, 0, 0, 'req-historical', 99, 500),
                  (901, 8, 55, 1, 1, 1, 0, 0, 'req-other-tenant', 55, 500)
                """);

        assertThat(mapper.selectById(userOne.getId(), SELF)).isNotNull();
        assertThat(mapper.selectById(userTwo.getId(), SELF)).isNull();
        assertThat(mapper.selectById(900L, SELF)).isNull();

        assertThat(mapper.selectById(userOne.getId(), ALL)).isNotNull();
        assertThat(mapper.selectById(userTwo.getId(), ALL)).isNotNull();
        assertThat(mapper.selectById(900L, ALL)).isNotNull();
        assertThat(mapper.selectById(901L, ALL)).isNull();

        assertThat(mapper.selectById(userOne.getId(), null)).isNull();
        assertThat(mapper.selectById(
                userOne.getId(), DataScope.system("group batch recovery"))).isNull();
    }

    private static GroupBatchTask task(String requestId, int totalCount) {
        GroupBatchTask row = new GroupBatchTask();
        row.setTenantId(TENANT_ID);
        row.setOwnerUserId(OPERATOR_ID);
        row.setTaskType(GroupBatchTaskType.REFRESH_LINK.code());
        row.setStatus(GroupBatchTaskStatus.PENDING.code());
        row.setTotalCount(totalCount);
        row.setSuccessCount(0);
        row.setFailedCount(0);
        row.setRequestId(requestId);
        row.setCreatedBy(OPERATOR_ID);
        row.setCreatedAt(500L);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_batch_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    task_type TINYINT NOT NULL,
                    status TINYINT NOT NULL,
                    total_count INT NOT NULL DEFAULT 0,
                    success_count INT NOT NULL DEFAULT 0,
                    failed_count INT NOT NULL DEFAULT 0,
                    request_id VARCHAR(64) NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    completed_at BIGINT,
                    CONSTRAINT uq_group_batch_task_request
                        UNIQUE (tenant_id, owner_user_id, request_id)
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis 与租户拦截器配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_batch_task_mapper_test;"
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
                    "mapper/group/GroupBatchTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupBatchTaskMapper groupBatchTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupBatchTaskMapper.class);
        }
    }
}
