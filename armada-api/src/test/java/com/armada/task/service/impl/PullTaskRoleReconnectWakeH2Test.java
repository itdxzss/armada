package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.state.PullTaskPullerAccountStateChangedSideEffect;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.scheduler.PullTaskExecutionDispatchScheduler;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 上线事件先唤醒建群人/管理员等待，真实 SQL 保留租约、暂停、租户和角色边界。 */
class PullTaskRoleReconnectWakeH2Test {

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private PullTaskPullerAccountStateChangedSideEffect sideEffect;
    private PullTaskOfflineRoleWaitProperties properties;
    private PullTaskExecutionDispatchScheduler scheduler;
    private final AtomicInteger wakeStatements = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = PullTaskNormalLinkH2Support.dataSource("roleWake" + System.nanoTime());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE pull_task_group_execution(id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "execution_status INT,reason_code VARCHAR(80),manual_paused INT,lock_owner VARCHAR(80),"
                + "next_run_at BIGINT,version INT,updated_at BIGINT)");
        jdbc.execute("CREATE TABLE pull_task_group_account(id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "group_execution_id BIGINT,account_id BIGINT,role_type INT,availability_status INT)");
        jdbc.update("INSERT INTO pull_task_group_execution VALUES(11,1,3,'GROUP_CREATOR_RECONNECTING',0,NULL,9000,5,1000)");
        jdbc.update("INSERT INTO pull_task_group_account VALUES(21,1,11,101,4,1)");
        var config = new MyBatisConfig();
        var interceptor = config.mybatisPlusInterceptor(config.tenantLineHandler());
        interceptor.addInnerInterceptor(new InnerInterceptor() {
            @Override
            public void beforeUpdate(Executor executor, MappedStatement statement, Object parameter) {
                if (statement.getId().endsWith(".wakeForReconnectedRole")) {
                    wakeStatements.incrementAndGet();
                }
            }
        });
        var sessions = new SqlSessionTemplate(PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                "mapper/task/PullTaskGroupExecutionMapper.xml", "mapper/task/PullTaskGroupAccountMapper.xml"));
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        scheduler = mock(PullTaskExecutionDispatchScheduler.class);
        properties = new PullTaskOfflineRoleWaitProperties();
        var service = new PullTaskPullerAccountStateServiceImpl(sessions.getMapper(PullTaskGroupAccountMapper.class),
                sessions.getMapper(PullTaskGroupExecutionMapper.class), new PullTaskPullerAccountStateResources(
                        null, event -> { }, new PullTaskExecutionDispatchTrigger(scheduler), properties,
                        mock(AccountProtocolLookupService.class)));
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        sideEffect = new PullTaskPullerAccountStateChangedSideEffect(
                (PullTaskPullerAccountStateService) proxy.getProxy(), mock(AccountProtocolLookupService.class));
        TenantContext.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void creatorReconnectionWakesBeforePullerEligibilityAndTriggersOnlyAfterCommit() {
        transactions.executeWithoutResult(status -> {
            online(1L);
            assertThat(nextRun()).isEqualTo(5_000L);
            verify(scheduler, never()).trigger();
        });
        assertThat(wakeStatements).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT version FROM pull_task_group_execution", Integer.class)).isEqualTo(6);
        verify(scheduler).trigger();
    }

    @Test
    void managerReconnectionDoesNotPostponeAnAlreadyDueExecution() {
        jdbc.update("UPDATE pull_task_group_execution SET reason_code='MANAGER_RECONNECTING',next_run_at=0,execution_status=2");
        jdbc.update("UPDATE pull_task_group_account SET role_type=1");
        online(1L);
        assertThat(nextRun()).isZero();
        verify(scheduler).trigger();
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual_paused=1", "lock_owner='other'", "execution_status=5", "reason_code='PULLER_UNAVAILABLE'"})
    void unrelatedPausedLeasedOrTerminalExecutionIsNotWoken(String change) {
        jdbc.update("UPDATE pull_task_group_execution SET " + change);
        online(1L);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role_type=2", "availability_status=4", "tenant_id=2", "account_id=102"})
    void unrelatedRemovedOrForeignRoleDoesNotWakeExecution(String change) {
        jdbc.update("UPDATE pull_task_group_account SET " + change);
        online(1L);
        assertUnchanged();
    }

    @Test
    void foreignTenantEventCannotWakeCurrentTenantExecution() {
        online(2L);
        assertUnchanged();
        assertThat(TenantContext.get()).isEqualTo(1L);
    }

    @Test
    void disabledTaskSwitchDoesNotIssueNewWakeSql() {
        properties.setEnabled(false);
        online(1L);
        assertUnchanged();
        assertThat(wakeStatements).hasValue(0);
    }

    @Test
    void rolledBackOnlineEventDoesNotWakeSchedulerOrPersistDeadline() {
        transactions.executeWithoutResult(status -> {
            online(1L);
            status.setRollbackOnly();
        });
        assertUnchanged();
    }

    private void online(long tenantId) {
        var account = new Account();
        account.setId(101L);
        sideEffect.afterStateChanged(account, new AccountStateChangedEvent(tenantId, 101L, "account-101",
                "OFFLINE", "ONLINE", 5_000L, "ONLINE", null, "protocol", null, null), 5_000L);
    }

    private long nextRun() {
        return jdbc.queryForObject("SELECT next_run_at FROM pull_task_group_execution", Long.class);
    }

    private void assertUnchanged() {
        assertThat(nextRun()).isEqualTo(9_000L);
        assertThat(jdbc.queryForObject("SELECT version FROM pull_task_group_execution", Integer.class)).isEqualTo(5);
        verify(scheduler, never()).trigger();
    }
}
