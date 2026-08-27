package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.impl.PullTaskStandardStartServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 普通群链接任务手动/自动共用启动服务的 H2 事务测试。 */
@SpringJUnitConfig(PullTaskStandardStartServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardStartServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMapper taskMapper;
    @Autowired private PullTaskStandardSettingMapper settingMapper;
    @Autowired private PullTaskStandardStartService startService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        DataScopeContext.open(DataScope.all(501L));
        PullTaskNormalLinkH2Support.resetSchema(dataSource,
                task(1L, 7L, "WAIT_START"),
                task(2L, 7L, "PAUSED"),
                task(3L, 8L, "WAIT_START"),
                unownedTask(5L, 7L, "WAIT_START"),
                setting(1L, 7L),
                setting(5L, 7L));
        reset(dispatchTrigger);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void startMovesWaitStartTaskToExecutingAndSignalsDispatcher() {
        startService.start(1L);

        PullTask task = taskMapper.selectLifecycle(1L);
        assertThat(task.getStatus()).isEqualTo("EXECUTING");
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(task.getStartedAt()).isEqualTo(900L);
        assertThat(settingMapper.selectByTaskId(1L).getRequiredManagerCount()).isEqualTo(1);
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void repeatedStartIsIdempotent() {
        startService.start(1L);
        startService.start(1L);

        assertThat(taskMapper.selectLifecycle(1L).getVersion()).isEqualTo(2);
        verify(dispatchTrigger, times(2)).dispatchAfterCommit();
    }

    @Test
    void missingSettingOrIllegalStateDoesNotStartTask() throws SQLException {
        assertThatThrownBy(() -> startService.start(2L))
                .isInstanceOf(BusinessException.class);

        execute(task(4L, 7L, "WAIT_START"));
        assertThatThrownBy(() -> startService.start(4L))
                .isInstanceOf(BusinessException.class);
        assertThat(taskMapper.selectLifecycle(4L).getStatus()).isEqualTo("WAIT_START");
    }

    @Test
    void startCannotCrossTenant() {
        assertThatThrownBy(() -> startService.start(3L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void administratorCannotStartHistoricalUnownedTask() {
        assertThatThrownBy(() -> startService.start(5L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        assertThat(taskMapper.selectLifecycle(5L).getStatus()).isEqualTo("WAIT_START");
        verify(dispatchTrigger, org.mockito.Mockito.never()).dispatchAfterCommit();
    }

    private static String task(long id, long tenantId, String status) {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, owner_user_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES (" + id + ", " + tenantId
                + ", 501, 'STANDARD', 'task', 'NORMAL_LINK', '" + status
                + "', 1, '{}', 100, 100)";
    }

    private static String unownedTask(long id, long tenantId, String status) {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, owner_user_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES (" + id + ", " + tenantId
                + ", NULL, 'STANDARD', 'historical-task', 'NORMAL_LINK', '" + status
                + "', 1, '{}', 100, 100)";
    }

    private static String setting(long taskId, long tenantId) {
        return "INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (" + tenantId + ", " + taskId
                + ", 0, 1, 1, 1, 0, 1, 0, 1, 0, 0, 11, 12, 13, "
                + "'manager', 'puller', 'station', 100, 100)";
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_start_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean
        PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean
        PullTaskStandardStartService startService(PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskStandardStartServiceImpl(
                    taskMapper, settingMapper, trigger, () -> 900L);
        }
    }
}
