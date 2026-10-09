package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.service.impl.PullTaskPullerAccountStateServiceImpl;
import com.armada.task.service.impl.PullTaskPullerAccountStateResources;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;

/** 真实账号快照与角色 Mapper 验证上线回调及进群恢复都不能复活终态账号。 */
class PullTaskOfflineRestoreTerminalH2Test {
    private PullTaskBothSwitchesOffH2Support h;
    private PullTaskPullerAccountStateService accountStates;
    private PullTaskManagerPullerContactTransactionService contact;
    private final AtomicInteger availabilityQueries = new AtomicInteger();
    private long roleId;

    @BeforeEach
    void setUp() throws Exception {
        h = new PullTaskBothSwitchesOffH2Support(new AccountTakeoverH2Support());
        h.accountSupport.properties.setEnabled(true);
        h.properties.setEnabled(true);
        h.accountSupport.sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
                .forEach(interceptor -> interceptor.addInnerInterceptor(new InnerInterceptor() {
                    @Override
                    public void beforeQuery(Executor executor, MappedStatement statement, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
                        if (statement.getId().endsWith(".selectRoleAvailabilitySnapshots")) {
                            availabilityQueries.incrementAndGet();
                        }
                    }
                }));
        accountStates = h.accountSupport.transactional(new PullTaskPullerAccountStateServiceImpl(
                h.roles, h.executions, new PullTaskPullerAccountStateResources(
                        mock(PullTaskStickyPullerTransactionService.class), event -> { },
                        new PullTaskExecutionDispatchTrigger(mock(PullTaskExecutionDispatchScheduler.class)), h.properties, h.lookup)),
                PullTaskPullerAccountStateService.class);
        var config = new MyBatisConfig();
        var sql = new SqlSessionTemplate(PullTaskNormalLinkH2Support.sqlSessionFactory(h.accountSupport.dataSource,
                config.mybatisPlusInterceptor(config.tenantLineHandler()), "mapper/task/PullTaskMapper.xml",
                "mapper/task/PullTaskStandardSettingMapper.xml", "mapper/task/PullTaskAccountActionMapper.xml"));
        contact = h.accountSupport.transactional(new PullTaskManagerPullerContactTransactionService(
                sql.getMapper(PullTaskMapper.class), sql.getMapper(PullTaskStandardSettingMapper.class), h.roles,
                sql.getMapper(PullTaskAccountActionMapper.class), new PullTaskManagerPullerContactResources(
                        h.executions, h.lookup, mock(ProtocolCommandOutboxService.class),
                        new PullTaskExecutionDispatchProperties(), h.properties), mock(PullTaskGroupProfileDispatcher.class)),
                PullTaskManagerPullerContactTransactionService.class);
        h.onlineAccount(901L, 89L);
        roleId = h.role(901L, 2, 3, 2);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"TRIPPED", "DESIRED_OFFLINE"})
    void onlineEventDoesNotRestoreTerminalPuller(String terminal) {
        terminal(terminal);
        accountStates.markOnline(1L, 901L, h.NOW);
        assertThat(h.roles.selectById(roleId).getAvailabilityStatus()).isEqualTo(3);
        assertThat(availabilityQueries).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRIPPED", "DESIRED_OFFLINE"})
    void contactAllocationDoesNotRestoreTerminalAssignedPuller(String terminal) {
        terminal(terminal);
        assertThat(contact.allocateDirectPullers(h.candidate(), h.NOW)).isEmpty();
        assertThat(h.roles.selectById(roleId).getAvailabilityStatus()).isEqualTo(3);
        assertThat(h.roles.selectByExecutionAndRole(111L, 2)).hasSize(1);
        assertThat(availabilityQueries).hasValue(1);
    }

    @Test
    void disabledOnlineEventPreservesLegacyRestoreWithoutAvailabilityQuery() {
        terminal("TRIPPED");
        h.properties.setEnabled(false);
        accountStates.markOnline(1L, 901L, h.NOW);
        assertThat(h.roles.selectById(roleId).getAvailabilityStatus()).isEqualTo(1);
        assertThat(availabilityQueries).hasValue(0);
    }

    @Test
    void disabledContactAllocationPreservesLegacyRestoreWithoutAvailabilityQuery() {
        terminal("DESIRED_OFFLINE");
        h.properties.setEnabled(false);
        assertThat(contact.allocateDirectPullers(h.candidate(), h.NOW)).hasSize(1);
        assertThat(h.roles.selectById(roleId).getAvailabilityStatus()).isEqualTo(1);
        assertThat(availabilityQueries).hasValue(0);
    }

    private void terminal(String terminal) {
        if ("TRIPPED".equals(terminal)) {
            h.jdbc.update("INSERT INTO account_takeover_breaker(tenant_id,account_id,window_started_at,kick_count,"
                    + "tripped_at,created_at,updated_at) VALUES(1,901,1000,10,1500,1000,1500)");
        } else {
            h.jdbc.update("UPDATE account_state SET desired_login_state=2 WHERE account_id=901");
        }
        // 旧资格查询仍返回在线账号，确保本例覆盖恢复入口的新增终态防线。
        assertThat(h.lookup.findEligiblePullerProtocolRefs(java.util.List.of(901L)))
                .extracting(ref -> ref.armadaAccountId()).containsExactly(901L);
    }
}
