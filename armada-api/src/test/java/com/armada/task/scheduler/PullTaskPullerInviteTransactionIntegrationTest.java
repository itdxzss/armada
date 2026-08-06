package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.PullTaskPullerInviteResultService;
import com.armada.task.service.impl.PullTaskPullerInviteResultServiceImpl;
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

/** EX-04 使用真实 Mapper XML 验证 Outbox 邀请、管理员轮询和一秒检查点。 */
@SpringJUnitConfig(PullTaskPullerInviteTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullerInviteTransactionIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskPullerInviteTransactionService service;
    @Autowired private PullTaskPullerInviteResultService resultService;

    private Long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
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
        execute("UPDATE pull_task_group_execution "
                + "SET execution_status=2, stage="
                + PullTaskExecutionStage.PULLER_INVITE.code()
                + ", version=5, group_jid='120363group@g.us' "
                + "WHERE id=" + executionId);
        insertRole(901L, "8613800000901", PullTaskGroupAccountRole.MANAGER, 1, true);
        insertRole(903L, "8613800000903", PullTaskGroupAccountRole.MANAGER, 2, true);
        insertRole(902L, "8613800000902", PullTaskGroupAccountRole.PULLER, 1, false);
        insertRole(904L, "8613800000904", PullTaskGroupAccountRole.PULLER, 2, false);
        seedProtocolAccounts();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void managersRotateOnePullerAtATimeAndCallbackEnforcesOneSecondInterval() {
        when(outboxService.enqueuePullTaskPullerInviteCommands(anyList()))
                .thenReturn(enqueued("cmd-invite-1"))
                .thenReturn(enqueued("cmd-invite-2"));

        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        assertThat(service.prepare(firstCandidate, "worker-1", 610L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskAccountAction first = inviteActions().get(0);
        assertInvite(first, 901L, 902L, "cmd-invite-1");
        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNextPullerIndex())
                .isEqualTo(1);
        assertThat(resultService.apply(callback(
                first, 901L, "8613800000902@s.whatsapp.net",
                PullTaskPullerInviteProtocolOutcome.SUCCESS, 620L))).isTrue();

        assertThat(claimedAt("too-early", 1_609L)).isEmpty();
        PullTaskGroupExecution secondCandidate = claim("worker-2", 1_610L, 2_000L);
        assertThat(service.prepare(secondCandidate, "worker-2", 1_620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskAccountAction second = inviteActions().get(1);
        assertInvite(second, 903L, 904L, "cmd-invite-2");
        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNextPullerIndex())
                .isZero();
        assertThat(resultService.apply(callback(
                second, 903L, "8613800000904@s.whatsapp.net",
                PullTaskPullerInviteProtocolOutcome.FAILED, 1_630L))).isTrue();

        assertThat(claimedAt("too-early-again", 2_619L)).isEmpty();
        PullTaskGroupExecution finishCandidate = claim("worker-3", 2_620L, 3_000L);
        assertThat(service.prepare(finishCandidate, "worker-3", 2_630L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(inviteActions())
                .extracting(PullTaskAccountAction::getActionStatus)
                .containsExactly(PullTaskActionStatus.SUCCESS.code(),
                        PullTaskActionStatus.FAILED.code());
    }

    @Test
    void submittedInviteKeepsItsFactAndIsNotRepublished() throws SQLException {
        when(outboxService.enqueuePullTaskPullerInviteCommands(anyList()))
                .thenReturn(enqueued("cmd-invite-1"));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 650L);
        service.prepare(firstCandidate, "worker-1", 610L);
        execute("UPDATE pull_task_group_execution SET next_run_at=0 WHERE id=" + executionId);

        PullTaskGroupExecution recovered = claim("worker-2", 700L, 1_000L);
        assertThat(service.prepare(recovered, "worker-2", 710L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        PullTaskAccountAction action = inviteActions().get(0);
        assertThat(action.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(action.getCommandId()).isEqualTo("cmd-invite-1");
        verify(outboxService, times(1)).enqueuePullTaskPullerInviteCommands(anyList());
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getNextManagerIndex()).isEqualTo(1);
        assertThat(saved.getNextRunAt()).isEqualTo(60_710L);
        assertThat(saved.getLockOwner()).isNull();
    }

    @Test
    void linkEntryPullerAlreadyInGroupIsNeverInvitedAgain() throws SQLException {
        when(outboxService.enqueuePullTaskPullerInviteCommands(anyList()))
                .thenReturn(enqueued("cmd-invite-1"));
        execute("UPDATE pull_task_group_account SET entry_mode=1, membership_status=2, "
                + "joined_at=550 WHERE group_execution_id=" + executionId
                + " AND account_id=902");
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        assertThat(service.prepare(candidate, "worker-1", 610L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        PullTaskAccountAction action = inviteActions().get(0);
        assertInvite(action, 901L, 904L, "cmd-invite-1");
    }

    @Test
    void inviteStageWithNoJoinedPullerReturnsToContactSelection() {
        TenantContext.set(7L);
        List<PullTaskGroupAccount> managers = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code());
        List<PullTaskGroupAccount> pullers = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code());
        for (int index = 0; index < pullers.size(); index++) {
            PullTaskGroupAccount puller = pullers.get(index);
            groupAccountMapper.updateMembership(puller.getId(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(), null, 550L);
            PullTaskAccountAction action = new PullTaskAccountAction();
            action.setTaskId(100L);
            action.setGroupExecutionId(executionId);
            action.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
            action.setActorGroupAccountId(managers.get(index).getId());
            action.setTargetGroupAccountId(puller.getId());
            action.setCreatedAt(500L);
            action.setUpdatedAt(500L);
            actionMapper.insertIfAbsent(action);
            actionMapper.markSubmitted(action.getId(), "cmd-failed-" + index, 510L);
            actionMapper.writeBackResult(action.getId(), PullTaskActionStatus.FAILED.code(),
                    "INVITE_FAILED", "邀请失败", 520L);
        }

        PullTaskExecutionDispatchResult result =
                service.prepare(claim("worker-1", 600L, 900L), "worker-1", 610L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(saved.getWaitResourceType()).isNull();
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()))
                .allMatch(row -> row.getReleasedAt() != null);
    }

    @Test
    void executionRaceRollsBackFailedPullerRelease() throws SQLException {
        TenantContext.set(7L);
        List<PullTaskGroupAccount> managers = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code());
        List<PullTaskGroupAccount> pullers = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code());
        for (int index = 0; index < pullers.size(); index++) {
            PullTaskGroupAccount puller = pullers.get(index);
            groupAccountMapper.updateMembership(puller.getId(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(), null, 550L);
            PullTaskAccountAction action = new PullTaskAccountAction();
            action.setTaskId(100L);
            action.setGroupExecutionId(executionId);
            action.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
            action.setActorGroupAccountId(managers.get(index).getId());
            action.setTargetGroupAccountId(puller.getId());
            action.setCreatedAt(500L);
            action.setUpdatedAt(500L);
            actionMapper.insertIfAbsent(action);
            actionMapper.markSubmitted(action.getId(), "cmd-failed-race-" + index, 510L);
            actionMapper.writeBackResult(action.getId(), PullTaskActionStatus.FAILED.code(),
                    "INVITE_FAILED", "邀请失败", 520L);
        }
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        execute("UPDATE pull_task_group_execution SET version=version+1 WHERE id=" + executionId);

        assertThatThrownBy(() -> service.prepare(candidate, "worker-1", 610L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("执行行租约已变化");

        TenantContext.set(7L);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()))
                .allMatch(row -> row.getReleasedAt() == null);
    }

    private void assertInvite(
            PullTaskAccountAction action, long actorAccountId,
            long targetAccountId, String commandId) {
        TenantContext.set(7L);
        PullTaskGroupAccount actor = groupAccountMapper.selectById(action.getActorGroupAccountId());
        PullTaskGroupAccount target = groupAccountMapper.selectById(action.getTargetGroupAccountId());
        assertThat(actor.getAccountId()).isEqualTo(actorAccountId);
        assertThat(target.getAccountId()).isEqualTo(targetAccountId);
        assertThat(target.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());
        assertThat(action.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(action.getCommandId()).isEqualTo(commandId);
        verify(outboxService).enqueuePullTaskPullerInviteCommands(argThat(commands ->
                commands.size() == 1
                        && commands.get(0).actionId().equals(action.getId())
                        && commands.get(0).actor().armadaAccountId().equals(actorAccountId)));
    }

    private PullTaskPullerInviteCallback callback(
            PullTaskAccountAction action,
            long actorAccountId,
            String targetJid,
            PullTaskPullerInviteProtocolOutcome outcome,
            long occurredAt) {
        return new PullTaskPullerInviteCallback(
                7L, 100L, executionId, action.getId(), actorAccountId,
                "protocol-" + actorAccountId, action.getCommandId(), 1,
                targetJid, outcome, null, null, false, occurredAt);
    }

    private List<PullTaskAccountAction> inviteActions() {
        TenantContext.set(7L);
        return actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.INVITE_TO_GROUP.code());
    }

    private static ProtocolCommandOutboxEnqueueResult enqueued(String commandId) {
        return new ProtocolCommandOutboxEnqueueResult(
                "pull-task:100", List.of(commandId), 1);
    }

    private void seedProtocolAccounts() {
        when(accountLookup.findActiveProtocolRefs(anyList())).thenReturn(List.of(
                account(901L), account(902L), account(903L), account(904L)));
    }

    private PullTaskGroupExecution claim(String owner, long now, long expiresAt) {
        return claimedAt(owner, now, expiresAt).get(0);
    }

    private List<PullTaskGroupExecution> claimedAt(String owner, long now) {
        return claimedAt(owner, now, now + 500L);
    }

    private List<PullTaskGroupExecution> claimedAt(String owner, long now, long expiresAt) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, expiresAt),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.PULLER_INVITE.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now);
    }

    private void insertRole(
            long accountId,
            String phone,
            PullTaskGroupAccountRole role,
            int seq,
            boolean inGroup) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
        row.setRoleSeq(seq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(role == PullTaskGroupAccountRole.MANAGER ? 1 : 2);
        row.setOccupiedAt(role == PullTaskGroupAccountRole.PULLER ? 500L : null);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        groupAccountMapper.insert(row);
        if (inGroup) {
            groupAccountMapper.updateMembership(row.getId(),
                    PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
            if (role == PullTaskGroupAccountRole.MANAGER) {
                groupAccountMapper.transitionAdminStatus(
                        row.getId(), List.of(PullTaskGroupAccountAdminStatus.PENDING.code()),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code(), 550L);
            }
        }
    }

    private static ProtocolAccountRef account(long accountId) {
        return new ProtocolAccountRef(accountId, ProtocolBackend.WEB,
                "protocol-" + accountId, "8613800000" + accountId);
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

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_puller_invite_test");
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
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml");
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
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean
        PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean
        AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean
        ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean
        PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean
        PullTaskPullerInviteResources resources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullerInviteResources(
                    executionMapper, accountLookup, outboxService, properties);
        }

        @Bean
        PullTaskPullerInviteTransactionService transactionService(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullerInviteResources resources) {
            return new PullTaskPullerInviteTransactionService(
                    taskMapper, groupAccountMapper, actionMapper, resources);
        }

        @Bean
        PullTaskPullerInviteResultService resultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskPullerInviteResultServiceImpl(
                    actionMapper, accountMapper, executionMapper);
        }
    }
}
