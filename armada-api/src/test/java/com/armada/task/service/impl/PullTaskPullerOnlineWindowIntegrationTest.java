package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.scheduler.PullTaskExecutionDispatchScheduler;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.PullTaskPullerAccountStateService.Unavailability;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper、租户插件和提交回调验证短在线窗口的角色恢复与有界唤醒。 */
@SpringJUnitConfig(PullTaskPullerOnlineWindowIntegrationTest.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullerOnlineWindowIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PullTaskPullerAccountStateService service;
    @Autowired private PullTaskGroupAccountMapper accounts;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private PullTaskExecutionDispatchScheduler scheduler;
    @Autowired private PullTaskStickyPullerTransactionService sticky;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        reset(scheduler, sticky);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        seed(7L, 72L, 76L, 344L);
        seed(8L, 73L, 77L, 345L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void onlineRestoresOnlyOwnOccupiedRoleAndWakesAfterCommitWithProgressIntact() {
        TenantContext.set(8L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.markOnline(7L, 1187L, 5_000L);
            assertThat(TenantContext.get()).isEqualTo(8L);
            verify(scheduler, never()).trigger();
        });
        verify(scheduler).trigger();
        TenantContext.set(7L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(1);
        assertThat(accounts.selectById(344L).getMembershipStatus()).isEqualTo(2);
        assertThat(accounts.selectById(344L).getJoinedAt()).isEqualTo(800L);
        assertThat(accounts.selectById(344L).getReleasedAt()).isNull();
        assertThat(executions.selectById(76L).getActivePullerGroupAccountId()).isEqualTo(344L);
        assertThat(executions.selectById(76L).getPullerAssignmentSeq()).isEqualTo(3L);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(5_000L);
        TenantContext.set(8L);
        assertThat(accounts.selectById(345L).getAvailabilityStatus()).isEqualTo(3);
        assertThat(executions.selectById(77L).getNextRunAt()).isEqualTo(9_000L);
        service.markOnline(7L, 1187L, 5_001L);
        verify(scheduler, times(1)).trigger();
    }

    @Test
    void rollbackRestoresNeitherRoleNorWakeSignal() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.markOnline(7L, 1187L, 5_000L);
            status.setRollbackOnly();
        });
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(3);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(9_000L);
        verifyNoInteractions(scheduler);
    }

    @Test
    void delayedOfflineCallbackCannotUndoANewerOnlineEvent() {
        service.markOnline(7L, 1187L, 5_000L);
        service.markUnavailable(7L, 1187L, Unavailability.OFFLINE, 4_900L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(1);
        assertThat(accounts.selectById(344L).getUpdatedAt()).isEqualTo(5_000L);
    }

    @Test
    void eligibleOnlineEventRestoresRoleEvenWhenMembershipWasUpdatedAfterEventTime() {
        jdbc.update("UPDATE pull_task_group_account SET updated_at=6000 WHERE id=344");
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(1);
        assertThat(accounts.selectById(344L).getJoinedAt()).isEqualTo(800L);
        assertThat(accounts.selectById(344L).getUpdatedAt()).isEqualTo(6_000L);
        verify(scheduler).trigger();
    }

    @Test
    void offlineKeepsStickyAndDoesNotEraseRiskCooldown() {
        jdbc.update("UPDATE pull_task_group_account SET availability_status=1, "
                + "unavailable_reason_code=NULL WHERE id=344");
        service.markUnavailable(7L, 1187L, Unavailability.OFFLINE, 4_000L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(3);
        assertThat(accounts.selectById(344L).getReleasedAt()).isNull();
        assertThat(executions.selectById(76L).getActivePullerGroupAccountId()).isEqualTo(344L);
        jdbc.update("UPDATE pull_task_group_account SET availability_status=2, "
                + "unavailable_reason_code='RATE_LIMIT',cooldown_until=20000 WHERE id=344");
        service.markUnavailable(7L, 1187L, Unavailability.OFFLINE, 4_100L);
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(2);
        assertThat(accounts.selectById(344L).getCooldownUntil()).isEqualTo(20_000L);
        verifyNoInteractions(sticky, scheduler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "INTERRUPTED", "ENDED", "COMPLETED", "WAIT_START"})
    void inactiveParentNeverWakes(String parentStatus) {
        jdbc.update("UPDATE pull_task SET status=? WHERE id=72", parentStatus);
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(9_000L);
        verifyNoInteractions(scheduler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"execution_status=4", "execution_status=5", "execution_status=6",
            "manual_paused=1", "lock_owner='worker',lock_expires_at=10000"})
    void terminalPausedOrLeasedExecutionNeverWakes(String update) {
        jdbc.update("UPDATE pull_task_group_execution SET " + update + " WHERE id=76");
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(9_000L);
        verifyNoInteractions(scheduler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"availability_status=4,unavailable_reason_code='PULLER_REPLACED'",
            "released_at=2000", "unavailable_reason_code='ACCOUNT_NOT_FOUND'",
            "unavailable_reason_code='ACCOUNT_NEED_REAUTH'"})
    void removedReleasedOrPermanentFailuresNeverRevive(String update) {
        jdbc.update("UPDATE pull_task_group_account SET " + update + " WHERE id=344");
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isNotEqualTo(1);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(9_000L);
        verifyNoInteractions(scheduler);
    }

    @Test
    void onlineDoesNotAdvanceOrdinaryBusinessDeadline() {
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2, "
                + "reason_code=NULL,next_run_at=20000 WHERE id=76");
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(accounts.selectById(344L).getAvailabilityStatus()).isEqualTo(1);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(20_000L);
        verifyNoInteractions(scheduler);
    }

    @Test
    void onlineWakePreservesPlannedWaveDispatchIntervalAndUnknownMembership() {
        jdbc.update("INSERT INTO pull_task_pull_wave "
                + "(id,tenant_id,task_id,group_execution_id,wave_no,wave_type,wave_status,"
                + "planned_call_count,next_call_seq,next_dispatch_at,created_at,updated_at) "
                + "VALUES (91,7,72,76,1,1,1,2,2,20000,100,100)");
        jdbc.update("UPDATE pull_task_group_execution SET active_pull_wave_id=91 WHERE id=76");
        jdbc.update("UPDATE pull_task_group_account SET membership_status=4, "
                + "pull_call_id=90,active_pull_attempt_id=92 WHERE id=344");
        service.markOnline(7L, 1187L, 5_000L);
        assertThat(executions.selectById(76L).getNextRunAt()).isEqualTo(20_000L);
        assertThat(accounts.selectById(344L).getMembershipStatus()).isEqualTo(4);
        assertThat(accounts.selectById(344L).getPullCallId()).isEqualTo(90L);
        assertThat(accounts.selectById(344L).getActivePullAttemptId()).isEqualTo(92L);
        assertThat(jdbc.queryForObject("SELECT next_dispatch_at FROM pull_task_pull_wave "
                + "WHERE id=91", Long.class)).isEqualTo(20_000L);
        verify(scheduler).trigger();
    }

    private void seed(long tenantId, long taskId, long executionId, long roleId) {
        jdbc.update("INSERT INTO pull_task "
                + "(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at) "
                + "VALUES (?,?,'window','NORMAL_LINK','EXECUTING','{}',100,100)", taskId, tenantId);
        jdbc.update("INSERT INTO pull_task_group_execution "
                + "(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,"
                + "stage,wait_resource_type,reason_code,next_run_at,active_puller_group_account_id,"
                + "puller_assignment_seq,created_at,updated_at) "
                + "VALUES (?,?,?,1,1,'members.txt',3,6,2,'ACCOUNT_NOT_ONLINE',9000,?,3,100,100)",
                executionId, tenantId, taskId, roleId);
        jdbc.update("INSERT INTO pull_task_group_account "
                + "(id,tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,role_seq,"
                + "membership_status,joined_at,availability_status,unavailable_reason_code,"
                + "occupied_at,created_at,updated_at) "
                + "VALUES (?,?,?,?,1187,'test-phone',2,1,2,800,3,'ACCOUNT_NOT_ONLINE',100,100,100)",
                roleId, tenantId, taskId, executionId);
    }

    @Configuration
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("puller_online_window_test");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml");
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
        @Bean PullTaskGroupAccountMapper accounts(SqlSessionTemplate session) {
            return session.getMapper(PullTaskGroupAccountMapper.class);
        }
        @Bean PullTaskGroupExecutionMapper executions(SqlSessionTemplate session) {
            return session.getMapper(PullTaskGroupExecutionMapper.class);
        }
        @Bean PullTaskStickyPullerTransactionService sticky() {
            return mock(PullTaskStickyPullerTransactionService.class);
        }
        @Bean PullTaskExecutionDispatchScheduler scheduler() {
            return mock(PullTaskExecutionDispatchScheduler.class);
        }
        @Bean PullTaskExecutionDispatchTrigger trigger(PullTaskExecutionDispatchScheduler scheduler) {
            return new PullTaskExecutionDispatchTrigger(scheduler);
        }
        @Bean PullTaskPullerAccountStateService service(PullTaskGroupAccountMapper accounts,
                PullTaskGroupExecutionMapper executions, PullTaskStickyPullerTransactionService sticky,
                ApplicationEventPublisher events, PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskPullerAccountStateServiceImpl(accounts, executions, sticky, events, trigger,
                    new com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties());
        }
    }
}
