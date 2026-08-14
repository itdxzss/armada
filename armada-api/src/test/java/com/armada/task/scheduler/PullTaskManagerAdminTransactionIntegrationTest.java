package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
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

/** 使用真实 Mapper 验证管理员设置准备、提交和租约 CAS 原子性。 */
@SpringJUnitConfig(PullTaskManagerAdminTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskManagerAdminTransactionIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private GroupExecutionAccountSelector promoterSelector;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskManagerAdminTransactionService service;

    private long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(promoterSelector, outboxService);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        PullTaskGroupExecution execution = draft();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution SET execution_status=2, stage="
                + PullTaskExecutionStage.MANAGER_ADMIN.code()
                + ", version=2, group_jid='120363group@g.us' WHERE id=" + executionId);
        PullTaskGroupAccount manager = manager();
        accountMapper.insertInitialized(manager);
        when(promoterSelector.findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of(
                new GroupExecutionAccount(
                        906L, "web", "promoter-906", "8613800000906", true)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstAttemptPersistsPromoterActionCommandAndManagerSubmittedFact() {
        when(outboxService.enqueuePullTaskManagerAdminCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-promote-1"), 1));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);
        PullTaskManagerAdminPreparation preparation =
                service.prepare(candidate, "worker-1", 600L);

        assertThat(preparation.ready()).isTrue();
        verify(promoterSelector, never()).findPullTaskAdminDiscoveryCandidates(
                7L, "120363group@g.us", 901L);
        assertThat(service.submitOrDefer(preparation.work(), 610L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.PROMOTE_MANAGER.code()).get(0);
        assertThat(action.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(action.getCommandId()).isEqualTo("cmd-promote-1");
        assertThat(action.getAttemptNo()).isEqualTo(1);
        PullTaskGroupAccount manager = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code()).get(0);
        assertThat(manager.getAdminStatus())
                .isEqualTo(PullTaskGroupAccountAdminStatus.SUBMITTED.code());
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(saved.getLockOwner()).isNull();
    }

    @Test
    void lostLeaseDoesNotCommitObservedPermissionFacts() throws SQLException {
        PullTaskGroupExecution candidate = claim("worker-1", 600L);
        PullTaskManagerAdminWork work = service.prepare(candidate, "worker-1", 600L).work();
        execute("UPDATE pull_task_group_execution SET version=version+1 WHERE id=" + executionId);

        assertThat(service.confirmManagerAdmin(work, 610L))
                .isEqualTo(PullTaskExecutionDispatchResult.LOST);

        TenantContext.set(7L);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.PROMOTE_MANAGER.code()).get(0);
        assertThat(action.getActionStatus()).isEqualTo(PullTaskActionStatus.PENDING.code());
        PullTaskGroupAccount manager = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code()).get(0);
        assertThat(manager.getAdminStatus())
                .isEqualTo(PullTaskGroupAccountAdminStatus.PENDING.code());
    }

    @Test
    void actionRaceRollsBackExecutionDeferral() throws SQLException {
        PullTaskGroupExecution candidate = claim("worker-1", 600L);
        PullTaskManagerAdminWork work = service.prepare(candidate, "worker-1", 600L).work();
        execute("UPDATE pull_task_account_action SET action_status="
                + PullTaskActionStatus.CANCELED.code()
                + " WHERE id=" + work.action().getId());

        assertThatThrownBy(() -> service.rejectPromoter(work, 610L))
                .isInstanceOf(IllegalStateException.class);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getVersion()).isEqualTo(candidate.getVersion());
        assertThat(saved.getLockOwner()).isEqualTo("worker-1");
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
    }

    @Test
    void missingStrictAdminReturnsDiscoveryWithoutCreatingPromoterAction() {
        when(promoterSelector.findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of());
        when(promoterSelector.findPullTaskAdminDiscoveryCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of(
                new GroupExecutionAccount(
                        906L, "web", "candidate-906", "8613800000906", false),
                new GroupExecutionAccount(
                        907L, "android", "candidate-907", "8613800000907", false)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        PullTaskManagerAdminPreparation preparation =
                service.prepare(candidate, "worker-1", 600L);

        assertThat(preparation.discoveryRequest()).isNotNull();
        assertThat(preparation.discoveryRequest().businessKey())
                .startsWith("manager-admin-discovery:");
        assertThat(preparation.discoveryRequest().actor().armadaAccountId()).isEqualTo(906L);
        assertThat(preparation.discoveryRequest().targetJids()).containsExactly(
                "8613800000906@s.whatsapp.net", "8613800000907@s.whatsapp.net");
        assertThat(accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PROMOTER.code())).isEmpty();
        assertThat(actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.PROMOTE_MANAGER.code())).isEmpty();
    }

    @Test
    void missingDiscoveryActorKeepsExistingUnavailableReason() {
        when(promoterSelector.findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of());
        when(promoterSelector.findPullTaskAdminDiscoveryCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of());
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.prepare(candidate, "worker-1", 600L).result())
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getReasonCode()).isEqualTo("MANAGER_ADMIN_ACTOR_UNAVAILABLE");
    }

    private PullTaskGroupExecution claim(String owner, long now) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, now + 500L),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.MANAGER_ADMIN.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(1);
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        row.setAdminStatus(PullTaskGroupAccountAdminStatus.PENDING.code());
        row.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        row.setJoinedAt(550L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(550L);
        return row;
    }

    private static PullTaskGroupExecution draft() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(100L);
        row.setSeq(1);
        row.setGroupLinkId(9_001L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setInviteCode("AAAA");
        row.setSourceLinkLineNo(1);
        row.setSourceFileIndex(1);
        row.setSourceFileName("material.txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
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

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_manager_admin_tx_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean GroupExecutionAccountSelector promoterSelector() {
            return mock(GroupExecutionAccountSelector.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean PullTaskManagerAdminCandidateSelector candidateSelector() {
            return new PullTaskManagerAdminCandidateSelector();
        }

        @Bean PullTaskManagerAdminResources resources(
                PullTaskGroupExecutionMapper executionMapper,
                GroupExecutionAccountSelector promoterSelector,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskManagerAdminResources(
                    executionMapper, promoterSelector, outboxService, properties);
        }

        @Bean PullTaskManagerAdminTransactionService service(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskManagerAdminCandidateSelector candidateSelector,
                PullTaskManagerAdminResources resources) {
            return new PullTaskManagerAdminTransactionService(
                    taskMapper, accountMapper, actionMapper, candidateSelector, resources);
        }
    }
}
