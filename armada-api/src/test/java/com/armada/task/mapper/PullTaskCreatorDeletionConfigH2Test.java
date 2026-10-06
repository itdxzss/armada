package com.armada.task.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.exception.BusinessException;
import com.armada.task.model.dto.PullTaskCreatorDeletionConfigDTO;
import com.armada.task.service.impl.PullTaskCreatorDeletionConfigService;
import com.armada.task.service.impl.PullTaskStandardStartServiceImpl;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真实 Mapper、租户拦截器与父任务行锁验证配置全生命周期。 */
@SpringJUnitConfig(PullTaskCreatorDeletionConfigH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class PullTaskCreatorDeletionConfigH2Test {
    @Autowired DataSource db;
    @Autowired PullTaskCreatorDeletionConfigService service;
    @Autowired PullTaskMapper tasks;
    @Autowired PullTaskCreatorDeletionConfigMapper configs;
    @Autowired PullTaskStandardSettingMapper settings;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setup() throws Exception {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(db);
        var jdbc = new JdbcTemplate(db);
        jdbc.update("""
                INSERT INTO pull_task (id,tenant_id,task_type,task_name,mode,creation_mode,status,
                  config_json,created_by,created_at,updated_at)
                VALUES (1,7,'STANDARD','test','NORMAL_LINK','NEW_GROUP','DRAFT','{}',11,1,1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_group_execution (tenant_id,task_id,seq,stage,execution_status,
                  source_file_index,source_file_name,valid_member_count,created_at,updated_at)
                VALUES (7,1,1,9,0,1,'test.txt',1,1,1)
                """);
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void oldTaskDefaultsOffAndDraftRoundTrips() {
        assertThat(tasks.selectLatestDraft(11L, com.armada.task.model.enums.PullTaskType.STANDARD, "DRAFT"))
                .isNotNull();
        assertThat(tasks.selectLifecycleForUpdate(1L).getCreatorDeleteAfterTakeover()).isZero();
        service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(true));
        assertThat(tasks.selectLifecycleForUpdate(1).getCreatorDeleteAfterTakeover()).isEqualTo(1);
        assertThat(tasks.selectLatestDraft(11L, com.armada.task.model.enums.PullTaskType.STANDARD, "DRAFT")
                .getCreatorDeleteAfterTakeover()).isEqualTo(1);
        service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(false));
        assertThat(tasks.selectLifecycleForUpdate(1).getCreatorDeleteAfterTakeover()).isZero();
    }

    @Test void tenantAndOwnerCannotEditAnotherTask() {
        assertThatThrownBy(() -> service.update(1, 12, new PullTaskCreatorDeletionConfigDTO(true)))
                .isInstanceOf(BusinessException.class);
        TenantContext.set(8L);
        assertThatThrownBy(() -> service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(true)))
                .isInstanceOf(BusinessException.class);
        assertThat(configs.updateBeforeStart(1, 1, 9)).isZero();
        TenantContext.set(7L);
        assertThat(tasks.selectLifecycleForUpdate(1).getCreatorDeleteAfterTakeover()).isZero();
    }

    @Test void otherModeAndStartedOrPausedTasksCannotEnable() {
        var jdbc = new JdbcTemplate(db);
        jdbc.update("UPDATE pull_task_group_execution SET stage=1 WHERE task_id=1");
        assertThatThrownBy(() -> service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(true)))
                .hasMessageContaining("仅支持新群模式");
        jdbc.update("UPDATE pull_task SET status='PAUSED',started_at=100 WHERE id=1");
        assertThatThrownBy(() -> service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(false)))
                .hasMessageContaining("冻结");
        assertThat(configs.updateBeforeStart(1, 1, 200)).isZero();
    }

    @Test void startupAndConfigEditSerializeOnSameParentLock() throws Exception {
        var jdbc = new JdbcTemplate(db);
        jdbc.update("UPDATE pull_task SET status='WAIT_START' WHERE id=1");
        var waiting = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                tasks.selectLifecycleForUpdate(1);
                var pending = waiting.submit(() -> {
                    TenantContext.set(7L);
                    entered.countDown();
                    try { service.update(1, 11, new PullTaskCreatorDeletionConfigDTO(false)); }
                    finally { TenantContext.clear(); }
                });
                result.set(pending);
                try {
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending.get(150, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    assertThat(tasks.updateStatusWithVersion(1, "WAIT_START", "EXECUTING", 1,
                            100L, null, 100L)).isEqualTo(1);
                } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            assertThatThrownBy(() -> result.get().get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            waiting.shutdown();
            assertThat(waiting.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.selectLifecycle(1).getStartedAt()).isEqualTo(100L);
            assertThat(tasks.selectLifecycle(1).getCreatorDeleteAfterTakeover()).isZero();
            assertThat(configs.updateBeforeStart(1, 1, 101)).isZero();
        } finally { waiting.shutdownNow(); }
    }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() { return PullTaskNormalLinkH2Support.dataSource("creator_delete_config"); }
        @Bean SqlSessionFactory factory(DataSource db, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(db, interceptor,
                    "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml", "mapper/task/PullTaskCreatorDeletionConfigMapper.xml");
        }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean PullTaskMapper tasks(SqlSessionTemplate template) { return template.getMapper(PullTaskMapper.class); }
        @Bean PullTaskGroupExecutionMapper executions(SqlSessionTemplate template) { return template.getMapper(PullTaskGroupExecutionMapper.class); }
        @Bean PullTaskStandardSettingMapper settings(SqlSessionTemplate template) { return template.getMapper(PullTaskStandardSettingMapper.class); }
        @Bean PullTaskCreatorDeletionConfigMapper configs(SqlSessionTemplate template) { return template.getMapper(PullTaskCreatorDeletionConfigMapper.class); }
        @Bean PlatformTransactionManager transactions(DataSource db) { return new DataSourceTransactionManager(db); }
        @Bean PullTaskCreatorDeletionConfigService service(PullTaskMapper tasks, PullTaskGroupExecutionMapper executions,
                PullTaskStandardSettingMapper settings, PullTaskCreatorDeletionConfigMapper configs) {
            return new PullTaskCreatorDeletionConfigService(tasks, executions, settings, configs,
                    mock(com.armada.account.service.AccountCreatorDeletionService.class));
        }
    }
}
