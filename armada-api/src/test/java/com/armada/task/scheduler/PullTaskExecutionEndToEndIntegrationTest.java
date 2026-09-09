package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.service.PullTaskContactSaveResultService;
import com.armada.task.service.PullTaskManagerJoinResultService;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.service.PullTaskGroupSettingsResultService;
import com.armada.task.service.impl.PullTaskGroupSettingsResultServiceImpl;
import com.armada.task.service.PullTaskManagerAdminResultService;
import com.armada.task.service.PullTaskPullerInviteResultService;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.armada.task.service.impl.PullTaskContactSaveResultServiceImpl;
import com.armada.task.service.impl.PullTaskManagerJoinResultServiceImpl;
import com.armada.task.service.impl.PullTaskManagerAdminResultServiceImpl;
import com.armada.task.service.impl.PullTaskPullerInviteResultServiceImpl;
import com.armada.task.service.impl.PullTaskProtocolResultCallbackServiceImpl;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import com.armada.task.service.impl.PullTaskPullCallResultCoordination;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** 用真实 Mapper XML 纵向验证链接校验到父任务收口的最小执行闭环。 */
@SpringJUnitConfig(PullTaskExecutionEndToEndIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskExecutionEndToEndIntegrationTest {

    private static final String GROUP_JID = "120363group@g.us";
    private static final AtomicBoolean MANAGER_PROMOTED = new AtomicBoolean();
    private static final AtomicBoolean MANAGER_AVAILABLE = new AtomicBoolean(true);
    private static final AtomicBoolean MANAGER_IN_GROUP = new AtomicBoolean(true);
    private static final AtomicReference<ProtocolProfile> PROTOCOL_PROFILE =
            new AtomicReference<>(ProtocolProfile.WEB);

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskExecutionDispatchCoordinator coordinator;
    @Autowired private PullTaskManagerJoinResultService managerJoinResultService;
    @Autowired private PullTaskManagerAdminResultService managerAdminResultService;
    @Autowired private PullTaskGroupSettingsResultService groupSettingsResultService;
    @Autowired private PullTaskContactSaveResultService contactSaveResultService;
    @Autowired private PullTaskPullerInviteResultService pullerInviteResultService;
    @Autowired private PullTaskProtocolResultCallbackService protocolResultCallbackService;
    @Autowired private PullTaskMemberQueryAwaitService memberQueryAwaitService;
    @Autowired private ProtocolCommandDispatchTrigger outboxDispatchTrigger;
    @Autowired private ProtocolCommandOutboxMapper outboxMapper;
    @Autowired private GroupJoinPort joinPort;
    @Autowired private GroupInviteLinkService inviteLinkService;
    private final List<Supplier<Boolean>> terminalCallbackReplays = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        MANAGER_PROMOTED.set(false);
        MANAGER_AVAILABLE.set(true);
        MANAGER_IN_GROUP.set(true);
        PROTOCOL_PROFILE.set(ProtocolProfile.WEB);
        terminalCallbackReplays.clear();
        reset(outboxDispatchTrigger, inviteLinkService);
        clearInvocations(joinPort, memberQueryAwaitService);
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(dataSource);
        seedTask();
        seedExecutionAndMaterial();
    }

    @AfterEach
    void tearDown() {
        PROTOCOL_PROFILE.set(ProtocolProfile.WEB);
        TenantContext.clear();
    }

    @ParameterizedTest(name = "{0} 普通拉群正常闭环")
    @EnumSource(ProtocolProfile.class)
    void executesOneLinkAndOneMaterialThroughClosing(ProtocolProfile profile) throws SQLException {
        PROTOCOL_PROFILE.set(profile);
        for (int round = 0; round < 50 && !"COMPLETED".equals(taskStatus()); round++) {
            long now = 1_000L + round * 1_000L;
            coordinator.dispatchOnce(now);
            sendPendingOutbox(now + 50L);
            applyManagerJoinCallbackIfSubmitted(now + 100L);
            sendPendingOutbox(now + 125L);
            applyManagerAdminCallbackIfSubmitted(now + 150L);
            sendPendingOutbox(now + 165L);
            applyGroupSettingsCallback(PullTaskAccountActionType.OPEN_MEMBER_ADD, now + 175L);
            sendPendingOutbox(now + 180L);
            applyGroupSettingsCallback(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL, now + 185L);
            sendPendingOutbox(now + 190L);
            applyContactCallbacksIfSubmitted(now + 200L);
            sendPendingOutbox(now + 250L);
            applyPullerInviteCallbacksIfSubmitted(now + 300L);
            sendPendingOutbox(now + 350L);
            applyBatchCallbacksIfSubmitted(now + 400L);
            sendPendingOutbox(now + 450L);
            applyMaterialAdminCallbackIfSubmitted(now + 500L);
        }

        TenantContext.set(7L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        assertTerminalCallbackReplaysAreSideEffectFree(execution);
        execution = executionMapper.selectById(execution.getId());
        PullTaskMaterialMember material = materialMapper.selectByExecution(execution.getId()).get(0);
        assertThat(execution.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(execution.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(material.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(material.getAdminStatus()).isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
        assertThat(callMapper.selectByExecution(execution.getId()))
                .singleElement()
                .extracting(call -> call.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(taskStatus()).isEqualTo("COMPLETED");
        assertPersistedOutboxChain(execution.getId(), profile);
        assertTerminalResourcesReleased(execution.getId());
        org.mockito.Mockito.verify(outboxDispatchTrigger,
                org.mockito.Mockito.atLeastOnce()).dispatchAfterCommit(anyList());
        verifyNoInteractions(memberQueryAwaitService);
    }

    @Test
    void managerShortageRemainsStableAcrossRecoveryCycles() throws SQLException {
        MANAGER_AVAILABLE.set(false);
        coordinator.dispatchOnce(1_000L);
        coordinator.dispatchOnce(2_000L);
        coordinator.dispatchOnce(3_000L);

        PullTaskGroupExecution waiting = executionMapper.selectByTaskId(100L).get(0);
        Long lastBusinessExecutedAt = waiting.getLastBusinessExecutedAt();
        assertManagerShortage(waiting);

        for (int cycle = 1; cycle <= 5; cycle++) {
            coordinator.dispatchOnce(3_000L + cycle * 30_000L);
            PullTaskGroupExecution current = executionMapper.selectById(waiting.getId());
            assertManagerShortage(current);
            assertThat(current.getLastBusinessExecutedAt()).isEqualTo(lastBusinessExecutedAt);
        }

        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_group_account")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_account_action")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox")).isZero();
        assertThat(taskStatus()).isEqualTo("EXECUTING");
        verifyNoInteractions(joinPort, outboxDispatchTrigger);
    }

    @Test
    void managerAvailabilityRestoresOnceAndCreatesOneJoinCommand() throws SQLException {
        MANAGER_AVAILABLE.set(false);
        coordinator.dispatchOnce(1_000L);
        coordinator.dispatchOnce(2_000L);
        coordinator.dispatchOnce(3_000L);
        PullTaskGroupExecution waiting = executionMapper.selectByTaskId(100L).get(0);
        assertManagerShortage(waiting);

        MANAGER_AVAILABLE.set(true);
        coordinator.dispatchOnce(waiting.getNextRunAt());
        PullTaskGroupExecution resumed = executionMapper.selectById(waiting.getId());
        assertThat(resumed.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(resumed.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(resumed.getWaitResourceType()).isNull();

        coordinator.dispatchOnce(waiting.getNextRunAt() + 1L);
        sendPendingOutbox(waiting.getNextRunAt() + 2L);
        coordinator.dispatchOnce(waiting.getNextRunAt() + 1_000L);

        assertThat(accountMapper.selectByExecutionAndRole(
                waiting.getId(),
                com.armada.task.model.enums.PullTaskGroupAccountRole.MANAGER.code()))
                .hasSize(1);
        assertThat(actionMapper.selectByExecutionAndType(
                waiting.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.getActionStatus())
                            .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
                    assertThat(action.getCommandId()).isNotBlank();
                });
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox")).isOne();
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox WHERE status="
                + ProtocolCommandOutboxStatus.SENT.code())).isOne();
        verifyNoInteractions(joinPort);
    }

    /** PL-R06 现状复现：UNKNOWN 且没有 groupJid 时，恢复分支会绕过 Outbox 同步重踩链接。 */
    @Test
    void unknownManagerJoinWithoutGroupJidCurrentlyRetriesOutsideOutbox() throws SQLException {
        coordinator.dispatchOnce(1_000L);
        sendPendingOutbox(1_050L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
        PullTaskManagerJoinCallback unknown = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.FAILED, null,
                "TIMEOUT", "协议结果未知", true, 1_100L);

        assertThat(managerJoinResultService.apply(unknown)).isTrue();
        PullTaskGroupExecution waitingForReconciliation =
                executionMapper.selectById(execution.getId());
        assertThat(waitingForReconciliation.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(waitingForReconciliation.getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");

        clearInvocations(joinPort);
        coordinator.dispatchOnce(waitingForReconciliation.getNextRunAt());

        verify(joinPort, times(1)).join(any());
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
        PullTaskGroupExecution afterRetry = executionMapper.selectById(execution.getId());
        assertThat(afterRetry.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
    }

    /** PL-A01 现状复现：待审批可暂停，但 APPROVAL 不在调度器自动恢复集合中。 */
    @Test
    void pendingApprovalPausesButCurrentlyCannotAutoResume() throws SQLException {
        coordinator.dispatchOnce(1_000L);
        sendPendingOutbox(1_050L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
        PullTaskManagerJoinCallback pendingApproval = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.PENDING_APPROVAL, GROUP_JID,
                "JOIN_PENDING_APPROVAL", "等待群主或管理员审批", false, 1_100L);

        assertThat(managerJoinResultService.apply(pendingApproval)).isTrue();
        PullTaskGroupExecution paused = executionMapper.selectById(execution.getId());
        assertThat(paused.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(paused.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(paused.getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.APPROVAL.code());
        assertThat(paused.getReasonCode()).isEqualTo("MANAGER_JOIN_PENDING_APPROVAL");
        assertThat(paused.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.PENDING_APPROVAL.code());
        assertThat(accountMapper.selectByExecutionAndRole(
                execution.getId(),
                com.armada.task.model.enums.PullTaskGroupAccountRole.MANAGER.code()))
                .singleElement()
                .satisfies(account -> assertThat(account.getMembershipStatus())
                        .isEqualTo(PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code()));

        long versionBefore = paused.getVersion();
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");
        clearInvocations(memberQueryAwaitService, joinPort);
        for (int cycle = 1; cycle <= 5; cycle++) {
            PullTaskExecutionDispatchStats stats = coordinator.dispatchOnce(
                    1_100L + cycle * 60_000L);
            assertThat(stats.claimed()).isZero();
        }

        PullTaskGroupExecution stillPaused = executionMapper.selectById(execution.getId());
        assertThat(stillPaused.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(stillPaused.getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.APPROVAL.code());
        assertThat(stillPaused.getVersion()).isEqualTo(versionBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
        assertThat(taskStatus()).isEqualTo("EXECUTING");
        verifyNoInteractions(memberQueryAwaitService, joinPort);
    }

    /** PL-I01/I02 现状复现：已知 JID 时只循环查成员，不查新邀请码。 */
    @ParameterizedTest(name = "{0} 已知 JID 的失效邀请码恢复")
    @EnumSource(value = ProtocolProfile.class, names = {"WEB", "ANDROID"})
    void revokedInviteWithKnownGroupJidCurrentlyNeverRefreshesInvite(
            ProtocolProfile profile) throws SQLException {
        PROTOCOL_PROFILE.set(profile);
        coordinator.dispatchOnce(1_000L);
        sendPendingOutbox(1_050L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
        PullTaskManagerJoinCallback revoked = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.FAILED, GROUP_JID,
                "INVITE_REVOKED", "群邀请链接已失效", false, 1_100L);

        assertThat(managerJoinResultService.apply(revoked)).isTrue();
        PullTaskGroupExecution retrying = executionMapper.selectById(execution.getId());
        assertThat(retrying.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(retrying.getReasonCode()).isEqualTo("INVITE_REVOKED");
        assertThat(retrying.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());

        MANAGER_IN_GROUP.set(false);
        when(inviteLinkService.refreshCurrentInviteCode(
                9_001L, GROUP_JID, "AAAA")).thenReturn(Optional.of("BBBB"));
        clearInvocations(inviteLinkService, memberQueryAwaitService, joinPort);
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");
        List<Integer> observedStatuses = new ArrayList<>();
        long now = retrying.getNextRunAt();
        for (int cycle = 0; cycle < 6; cycle++) {
            PullTaskExecutionDispatchStats stats = coordinator.dispatchOnce(now + cycle * 60_000L);
            assertThat(stats.claimed()).isOne();
            observedStatuses.add(executionMapper.selectById(execution.getId())
                    .getExecutionStatus());
        }

        assertThat(observedStatuses).containsExactly(
                PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                PullTaskExecutionStatus.EXECUTING.code());
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
        verify(memberQueryAwaitService, times(3)).readOrDefer(
                anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyLong());
        verify(inviteLinkService, org.mockito.Mockito.never())
                .refreshCurrentInviteCode(anyLong(), any(), any());
        verifyNoInteractions(joinPort);
    }

    @Test
    void lateConflictingManagerJoinCannotOverwriteSuccessfulFact() throws SQLException {
        coordinator.dispatchOnce(1_000L);
        sendPendingOutbox(1_050L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
        PullTaskManagerJoinCallback success = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.JOINED, GROUP_JID,
                null, null, false, 1_100L);
        assertThat(managerJoinResultService.apply(success)).isTrue();

        PullTaskGroupExecution advanced = executionMapper.selectById(execution.getId());
        long versionBefore = advanced.getVersion();
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");
        PullTaskManagerJoinCallback staleFailure = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.FAILED, GROUP_JID,
                "INVITE_REVOKED", "迟到的失效结果", false, 1_000L);

        for (int replay = 0; replay < 3; replay++) {
            assertThat(managerJoinResultService.apply(staleFailure)).isFalse();
        }
        PullTaskGroupExecution unchanged = executionMapper.selectById(execution.getId());
        assertThat(unchanged.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(unchanged.getVersion()).isEqualTo(versionBefore);
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
    }

    /** PL-S04 现状复现：终态后首个 SENT 回调整个回滚，协议事实不可审计。 */
    @Test
    void lateManagerJoinAfterTerminalCurrentlyRollsBackProtocolFact() throws SQLException {
        coordinator.dispatchOnce(1_000L);
        sendPendingOutbox(1_050L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskAccountAction action = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
        execute("UPDATE pull_task_group_execution SET execution_status="
                + PullTaskExecutionStatus.ABANDONED.code()
                + ", finished_at=1200, lock_owner=NULL, lock_expires_at=NULL, "
                + "version=version+1, updated_at=1200 WHERE id=" + execution.getId());
        PullTaskGroupExecution terminal = executionMapper.selectById(execution.getId());
        long terminalVersion = terminal.getVersion();
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");
        PullTaskManagerJoinCallback lateSuccess = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.JOINED, GROUP_JID,
                null, null, false, 1_300L);

        assertThatThrownBy(() -> managerJoinResultService.apply(lateSuccess))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("执行行结果 CAS 失败");

        PullTaskGroupExecution stillTerminal = executionMapper.selectById(execution.getId());
        assertThat(stillTerminal.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.ABANDONED.code());
        assertThat(stillTerminal.getVersion()).isEqualTo(terminalVersion);
        assertThat(actionMapper.selectByCommandId(action.getCommandId()).getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(accountMapper.selectByExecutionAndRole(
                execution.getId(),
                com.armada.task.model.enums.PullTaskGroupAccountRole.MANAGER.code()))
                .singleElement()
                .satisfies(account -> assertThat(account.getMembershipStatus())
                        .isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code()));
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
    }

    private static void assertManagerShortage(PullTaskGroupExecution execution) {
        assertThat(execution.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(execution.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(execution.getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.MANAGER.code());
        assertThat(execution.getReasonCode()).isEqualTo("MANAGER_UNAVAILABLE");
        assertThat(execution.getReasonMessage()).contains("缺口人数=1");
        assertThat(execution.getLockOwner()).isNull();
    }

    private void assertPersistedOutboxChain(
            long executionId, ProtocolProfile profile) throws SQLException {
        int businessCommands = queryInt(
                "SELECT COUNT(*) FROM pull_task_account_action "
                        + "WHERE group_execution_id=" + executionId
                        + " AND command_id IS NOT NULL")
                + queryInt("SELECT COUNT(*) FROM pull_task_pull_call "
                        + "WHERE group_execution_id=" + executionId
                        + " AND command_id IS NOT NULL")
                + queryInt("SELECT COUNT(*) FROM pull_task_material_member "
                        + "WHERE group_execution_id=" + executionId
                        + " AND admin_command_id IS NOT NULL");
        int outboxCommands = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");

        assertThat(businessCommands).isGreaterThan(0);
        assertThat(outboxCommands).isEqualTo(businessCommands);
        assertThat(queryInt(
                "SELECT COUNT(DISTINCT command_id) FROM protocol_command_outbox"))
                .isEqualTo(outboxCommands);
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                + "WHERE tenant_id<>7 OR batch_id<>'pull-task:100' OR status<>2 "
                + "OR payload_json IS NULL OR trace_id IS NULL"))
                .isZero();
        assertOutboxRouting(profile, outboxCommands);
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_account_action a "
                + "LEFT JOIN protocol_command_outbox o "
                + "ON o.command_id=a.command_id "
                + "AND o.aggregate_type='PULL_TASK_ACCOUNT_ACTION' "
                + "AND o.aggregate_id=a.id "
                + "WHERE a.group_execution_id=" + executionId
                + " AND a.command_id IS NOT NULL AND o.id IS NULL"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_pull_call c "
                + "LEFT JOIN protocol_command_outbox o "
                + "ON o.command_id=c.command_id "
                + "AND o.aggregate_type='PULL_TASK_PULL_CALL' "
                + "AND o.aggregate_id=c.id "
                + "WHERE c.group_execution_id=" + executionId
                + " AND c.command_id IS NOT NULL AND o.id IS NULL"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_material_member m "
                + "LEFT JOIN protocol_command_outbox o "
                + "ON o.command_id=m.admin_command_id "
                + "AND o.aggregate_type='PULL_TASK_MATERIAL_MEMBER' "
                + "AND o.aggregate_id=m.id "
                + "WHERE m.group_execution_id=" + executionId
                + " AND m.admin_command_id IS NOT NULL AND o.id IS NULL"))
                .isZero();
    }

    private void assertTerminalResourcesReleased(long executionId) throws SQLException {
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_group_execution WHERE id="
                + executionId
                + " AND (link_occupancy_key IS NOT NULL OR lock_owner IS NOT NULL)"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM pull_task_group_account WHERE "
                + "group_execution_id=" + executionId
                + " AND role_type="
                + com.armada.task.model.enums.PullTaskGroupAccountRole.PULLER.code()
                + " AND (released_at IS NULL OR occupancy_key IS NOT NULL)"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox WHERE status<>"
                + ProtocolCommandOutboxStatus.SENT.code()))
                .isZero();
    }

    private void assertOutboxRouting(
            ProtocolProfile profile, int outboxCommands) throws SQLException {
        int webCommands = queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                + "WHERE protocol_backend='WEB'");
        int androidCommands = queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                + "WHERE protocol_backend='ANDROID'");
        assertThat(webCommands + androidCommands).isEqualTo(outboxCommands);
        switch (profile) {
            case WEB -> {
                assertThat(webCommands).isEqualTo(outboxCommands);
                assertThat(androidCommands).isZero();
            }
            case ANDROID -> {
                assertThat(webCommands).isZero();
                assertThat(androidCommands).isEqualTo(outboxCommands);
            }
            case MIXED -> {
                assertThat(webCommands).isPositive();
                assertThat(androidCommands).isPositive();
            }
        }
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                + "WHERE protocol_backend='WEB' "
                + "AND kafka_topic<>'" + ProtocolMasterCommandProperties.DEFAULT_TOPIC + "'"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                + "WHERE protocol_backend='ANDROID' AND ("
                + "(command_type='group.join.requested' AND kafka_topic<>'"
                + ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC + "') OR "
                + "(command_type<>'group.join.requested' AND kafka_topic<>'"
                + ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC + "'))"))
                .isZero();
    }

    private void applyManagerJoinCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code());
        if (actions.size() != 1
                || actions.get(0).getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
            return;
        }
        PullTaskAccountAction action = actions.get(0);
        assertCommandSent(action.getCommandId());
        PullTaskManagerJoinCallback callback = new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.JOINED, GROUP_JID,
                null, null, false, occurredAt);
        applyAndReplay(() -> managerJoinResultService.apply(callback));
    }

    private void applyContactCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.SAVE_CONTACT.code());
        for (PullTaskAccountAction action : actions) {
            if (action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
                continue;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            assertCommandSent(action.getCommandId());
            PullTaskContactSaveCallback callback = new PullTaskContactSaveCallback(
                    7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                    "account-" + actor.getAccountId(), action.getCommandId(), 1,
                    PullTaskContactSaveOutcome.SUCCESS, null, null, false, occurredAt);
            applyAndReplay(() -> contactSaveResultService.apply(callback));
        }
    }

    private void applyManagerAdminCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
        if (actions.size() != 1
                || actions.get(0).getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
            return;
        }
        PullTaskAccountAction action = actions.get(0);
        PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
        PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
        assertCommandSent(action.getCommandId());
        PullTaskManagerAdminCallback callback = new PullTaskManagerAdminCallback(
                7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                "account-" + actor.getAccountId(), action.getCommandId(), action.getAttemptNo(),
                WhatsappJids.userJid(target.getAccountPhone()),
                PullTaskManagerAdminProtocolOutcome.SUCCESS,
                null, null, false, occurredAt);
        boolean applied = applyAndReplay(() -> managerAdminResultService.apply(callback));
        if (applied) {
            MANAGER_PROMOTED.set(true);
        }
    }

    private void applyGroupSettingsCallback(
            PullTaskAccountActionType actionType, long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        for (PullTaskAccountAction action : actionMapper.selectByExecutionAndType(
                execution.getId(), actionType.code())) {
            if (action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
                continue;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            assertCommandSent(action.getCommandId());
            PullTaskGroupSettingsCallback callback = new PullTaskGroupSettingsCallback(
                    7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                    "account-" + actor.getAccountId(), action.getCommandId(),
                    action.getAttemptNo(),
                    PullTaskGroupSettingsProtocolOutcome.SUCCESS, null, null, null,
                    occurredAt);
            applyAndReplay(() -> groupSettingsResultService.apply(callback));
        }
    }

    /** 本地模拟 publisher 的状态机；只有完成 SENT 后，测试才投递对应协议回调。 */
    private void sendPendingOutbox(long now) {
        List<ProtocolCommandOutbox> pending = outboxMapper.selectDispatchable(
                ProtocolCommandOutboxStatus.PENDING.code(), now, 100);
        if (pending.isEmpty()) {
            return;
        }
        List<Long> ids = pending.stream().map(ProtocolCommandOutbox::getId).toList();
        String publisherId = "stateful-sim-publisher";
        assertThat(outboxMapper.markLocked(ids, publisherId, now)).isEqualTo(ids.size());
        List<ProtocolCommandOutbox> locked = outboxMapper.selectLockedBy(ids, publisherId, now);
        assertThat(locked).hasSize(ids.size());
        assertThat(outboxMapper.markDispatching(locked, now + 1L)).isEqualTo(ids.size());
        assertThat(outboxMapper.markSentBatch(locked, now + 2L)).isEqualTo(ids.size());
    }

    private void applyPullerInviteCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.INVITE_TO_GROUP.code());
        for (PullTaskAccountAction action : actions) {
            if (action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
                continue;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
            assertCommandSent(action.getCommandId());
            PullTaskPullerInviteCallback callback = new PullTaskPullerInviteCallback(
                    7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                    "account-" + actor.getAccountId(), action.getCommandId(), 1,
                    WhatsappJids.userJid(target.getAccountPhone()),
                    PullTaskPullerInviteProtocolOutcome.SUCCESS,
                    null, null, false, occurredAt);
            applyAndReplay(() -> pullerInviteResultService.apply(callback));
        }
    }

    private void applyBatchCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        for (var call : callMapper.selectByExecution(execution.getId())) {
            if (call.getCallStatus() != PullTaskPullCallStatus.SUBMITTED.code()) {
                continue;
            }
            long resultAt = occurredAt;
            for (PullTaskGroupAccount station : accountMapper.selectByExecutionAndRole(
                    execution.getId(), com.armada.task.model.enums.PullTaskGroupAccountRole.STATION.code())) {
                if (call.getId().equals(station.getPullCallId())) {
                    applyBatchParticipant(call, station.getAccountPhone(), resultAt++);
                }
            }
            for (PullTaskMaterialMember material : materialMapper.selectByExecution(execution.getId())) {
                if (call.getId().equals(material.getPullCallId())) {
                    applyBatchParticipant(call, material.getNormalizedPhone(), resultAt++);
                }
            }
        }
    }

    private void applyBatchParticipant(
            com.armada.task.model.entity.PullTaskPullCall call,
            String phone,
            long occurredAt) {
        assertCommandSent(call.getCommandId());
        PullTaskBatchParticipantCallback callback = new PullTaskBatchParticipantCallback(
                        7L, 100L, call.getGroupExecutionId(), call.getId(),
                        call.getPullerAccountId(), "account-" + call.getPullerAccountId(),
                        call.getCommandId(), 1, WhatsappJids.userJid(phone),
                        PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                        null, null, false, occurredAt);
        applyAndReplay(() -> protocolResultCallbackService.handlePullCallParticipant(callback));
    }

    private void applyMaterialAdminCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskMaterialMember> submitted = materialMapper
                .selectByExecution(execution.getId()).stream()
                .filter(material -> material.getAdminStatus()
                        == PullTaskMaterialAdminStatus.SUBMITTED.code())
                .toList();
        if (submitted.isEmpty()) {
            return;
        }
        PullTaskGroupAccount manager = accountMapper.selectByExecutionAndRole(
                execution.getId(),
                com.armada.task.model.enums.PullTaskGroupAccountRole.MANAGER.code()).get(0);
        for (PullTaskMaterialMember material : submitted) {
            assertCommandSent(material.getAdminCommandId());
            PullTaskMaterialAdminCallback callback = new PullTaskMaterialAdminCallback(
                    7L, 100L, execution.getId(), material.getId(), manager.getAccountId(),
                    "account-" + manager.getAccountId(), material.getAdminCommandId(), 1,
                    material.getWaJid(), PullTaskMaterialAdminProtocolOutcome.SUCCESS,
                    null, null, false, occurredAt);
            applyAndReplay(() -> protocolResultCallbackService.handleMaterialAdmin(callback));
        }
    }

    private void assertCommandSent(String commandId) {
        try {
            assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox "
                    + "WHERE command_id='" + commandId + "' AND status="
                    + ProtocolCommandOutboxStatus.SENT.code())).isOne();
        } catch (SQLException exception) {
            throw new IllegalStateException("读取协议命令发送状态失败: " + commandId, exception);
        }
    }

    private boolean applyAndReplay(Supplier<Boolean> callback) {
        boolean applied = callback.get();
        assertThat(applied).isTrue();
        callback.get();
        terminalCallbackReplays.add(callback);
        return applied;
    }

    private void assertTerminalCallbackReplaysAreSideEffectFree(
            PullTaskGroupExecution execution) throws SQLException {
        assertThat(terminalCallbackReplays).hasSize(12);
        int outboxBefore = queryInt("SELECT COUNT(*) FROM protocol_command_outbox");
        long versionBefore = execution.getVersion();
        terminalCallbackReplays.forEach(Supplier::get);
        assertThat(queryInt("SELECT COUNT(*) FROM protocol_command_outbox"))
                .isEqualTo(outboxBefore);
        assertThat(executionMapper.selectById(execution.getId()).getVersion())
                .isEqualTo(versionBefore);
    }

    private void seedTask() throws SQLException {
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (7, 100, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
    }

    private void seedExecutionAndMaterial() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setTaskId(100L);
        execution.setSeq(1);
        execution.setGroupLinkId(9_001L);
        execution.setNormalizedLink("chat.whatsapp.com/AAAA");
        execution.setInviteCode("AAAA");
        execution.setSourceLinkLineNo(1);
        execution.setSourceFileIndex(1);
        execution.setSourceFileName("material.txt");
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);
        execution.setCreatedAt(100L);
        execution.setUpdatedAt(100L);
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setGroupExecutionId(execution.getId());
        material.setMemberSeq(1);
        material.setSourceLineNo(1);
        material.setNormalizedPhone("8613900000001");
        material.setAdminRequired(1);
        material.setCreatedAt(100L);
        material.setUpdatedAt(100L);
        materialMapper.batchInsert(List.of(material));
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String taskStatus() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM pull_task WHERE id=100")) {
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private int queryInt(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static ProtocolAccountRef manager() {
        return account(901L, PROTOCOL_PROFILE.get().managerBackend);
    }

    private static ProtocolAccountRef promoter() {
        return account(906L, PROTOCOL_PROFILE.get().managerBackend);
    }

    private static ProtocolAccountRef puller() {
        return account(902L, PROTOCOL_PROFILE.get().pullerBackend);
    }

    private static ProtocolAccountRef station() {
        return account(903L, PROTOCOL_PROFILE.get().stationBackend);
    }

    private static ProtocolAccountRef account(long id, ProtocolBackend backend) {
        return new ProtocolAccountRef(id, backend,
                "account-" + id, "8613800000" + id);
    }

    private enum ProtocolProfile {
        WEB(ProtocolBackend.WEB, ProtocolBackend.WEB, ProtocolBackend.WEB),
        ANDROID(ProtocolBackend.ANDROID, ProtocolBackend.ANDROID, ProtocolBackend.ANDROID),
        MIXED(ProtocolBackend.WEB, ProtocolBackend.ANDROID, ProtocolBackend.WEB);

        private final ProtocolBackend managerBackend;
        private final ProtocolBackend pullerBackend;
        private final ProtocolBackend stationBackend;

        ProtocolProfile(
                ProtocolBackend managerBackend,
                ProtocolBackend pullerBackend,
                ProtocolBackend stationBackend) {
            this.managerBackend = managerBackend;
            this.pullerBackend = pullerBackend;
            this.stationBackend = stationBackend;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_execution_e2e_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
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

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean ProtocolCommandOutboxMapper protocolCommandOutboxMapper(
                SqlSessionTemplate template) {
            return template.getMapper(ProtocolCommandOutboxMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            AccountProtocolLookupService lookup = mock(AccountProtocolLookupService.class);
            when(lookup.findRandomOnlineNormalPullerByGroupId(88L))
                    .thenAnswer(invocation -> MANAGER_AVAILABLE.get()
                            ? Optional.of(manager()) : Optional.empty());
            when(lookup.findOnlineNormalPullersByGroupId(89L))
                    .thenAnswer(invocation -> List.of(puller()));
            when(lookup.findOnlineNormalByGroupId(90L))
                    .thenAnswer(invocation -> List.of(station()));
            when(lookup.findActiveProtocolRef(901L))
                    .thenAnswer(invocation -> Optional.of(manager()));
            when(lookup.findActiveProtocolRef(902L))
                    .thenAnswer(invocation -> Optional.of(puller()));
            when(lookup.findActiveProtocolRef(903L))
                    .thenAnswer(invocation -> Optional.of(station()));
            when(lookup.findActiveProtocolRefs(anyList()))
                    .thenAnswer(invocation -> List.of(manager(), puller(), station()));
            when(lookup.findEligiblePullerProtocolRefs(anyList()))
                    .thenAnswer(invocation -> List.of(puller()));
            when(lookup.findOnlineProtocolRefs(anyList()))
                    .thenAnswer(invocation -> MANAGER_AVAILABLE.get()
                            ? List.of(manager()) : List.of());
            return lookup;
        }

        @Bean GroupJoinPort joinPort() {
            GroupJoinPort port = mock(GroupJoinPort.class);
            when(port.join(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new GroupJoinResult(GROUP_JID, GroupJoinOutcome.JOINED));
            return port;
        }

        @Bean GroupInviteLinkService groupInviteLinkService() {
            return mock(GroupInviteLinkService.class);
        }

        @Bean GroupMemberListPort memberListPort() {
            GroupMemberListPort port = mock(GroupMemberListPort.class);
            when(port.list(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> List.of(
                    participant(manager(), MANAGER_PROMOTED.get()),
                    participant(promoter(), true),
                    participant(puller(), false),
                    participant(station(), false),
                    new GroupParticipantResult(
"8613900000001@s.whatsapp.net", null, "8613900000001",
                            true, false, "admin")));
            return port;
        }

        @Bean PullTaskMemberQueryAwaitService memberQueryAwaitService() {
            PullTaskMemberQueryAwaitService service = mock(PullTaskMemberQueryAwaitService.class);
            when(service.readOrDefer(
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(invocation -> {
                        PullTaskMemberQueryRequest request = invocation.getArgument(1);
                        List<PullTaskMemberFact> members = request.targetJids().stream()
                                .map(target -> new PullTaskMemberFact(
                                        target, target, target.substring(0, target.indexOf('@')),
                                        !target.startsWith(manager().wsPhone())
                                                || MANAGER_IN_GROUP.get(),
                                        target.startsWith(promoter().wsPhone())
                                        || target.startsWith("8613900000001")
                                        || target.startsWith(manager().wsPhone())
                                        && MANAGER_PROMOTED.get()))
                                .toList();
                        return PullTaskMemberQueryResult.available(701L, members);
                    });
            return service;
        }

        @Bean ContactPort contactPort() {
            return mock(ContactPort.class);
        }

        @Bean GroupParticipantPort participantPort() {
            GroupParticipantPort port = mock(GroupParticipantPort.class);
            when(port.updateParticipants(
                    any(ProtocolAccountRef.class),
                    org.mockito.ArgumentMatchers.anyString(), anyList(),
                    any(GroupParticipantAction.class)))
                    .thenAnswer(invocation -> {
                        List<String> jids = invocation.getArgument(2);
                        return new GroupParticipantBatchResult(false, jids.stream()
                                .map(jid -> new GroupParticipantBatchResult.Item(jid, "OK", "200"))
                                .toList());
                    });
            return port;
        }

        private static GroupParticipantResult participant(
                ProtocolAccountRef account, boolean admin) {
            return new GroupParticipantResult(
                    account.wsPhone() + "@s.whatsapp.net", account.wsPhone() + "@s.whatsapp.net",
                    account.wsPhone(),
                    admin, false, admin ? "admin" : null);
        }

        @Bean PullTaskParentCompletionService parentCompletion(
                PullTaskMapper taskMapper, PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskParentCompletionService(taskMapper, executionMapper);
        }

        @Bean PullTaskExecutionTransactionService executionTransactions(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskParentCompletionService parentCompletion) {
            return new PullTaskExecutionTransactionService(
                    taskMapper, settingMapper, executionMapper,
                    org.mockito.Mockito.mock(com.armada.group.service.GroupFolderService.class));
        }

        @Bean PullTaskLinkValidationProcessor linkProcessor(
                PullTaskExecutionTransactionService transactions) {
            return new PullTaskLinkValidationProcessor(transactions);
        }

        @Bean PullTaskManagerJoinProcessor managerJoinProcessor(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper, PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper, AccountProtocolLookupService lookup,
                PullTaskParentCompletionService parentCompletion, GroupJoinPort joinPort,
                PullTaskMemberQueryAwaitService memberQueryAwaitService,
                GroupInviteLinkService inviteLinkService,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskExecutionTransactionService executionTransactions) {
            PullTaskManagerJoinResources resources = new PullTaskManagerJoinResources(
                    executionMapper, lookup, parentCompletion, outboxService, properties);
            PullTaskManagerJoinTransactionService transactions =
                    new PullTaskManagerJoinTransactionService(
                            taskMapper, settingMapper, accountMapper, actionMapper, resources);
            PullTaskManagerJoinProtocolExecutor protocolExecutor =
                    new PullTaskManagerJoinProtocolExecutor(
                            joinPort, inviteLinkService);
            return new PullTaskManagerJoinProcessor(
                    executionTransactions, transactions,
                    mock(PullTaskSupplementManagerProcessor.class),
                    protocolExecutor, memberQueryAwaitService);
        }

        @Bean ProtocolCommandDispatchTrigger protocolCommandDispatchTrigger() {
            return mock(ProtocolCommandDispatchTrigger.class);
        }

        @Bean ProtocolAccountCommandProperties protocolAccountCommandProperties() {
            return new ProtocolAccountCommandProperties();
        }

        @Bean ProtocolMasterCommandProperties protocolMasterCommandProperties() {
            return new ProtocolMasterCommandProperties();
        }

        @Bean ProtocolAndroidCommandProperties protocolAndroidCommandProperties() {
            return new ProtocolAndroidCommandProperties();
        }

        @Bean NormalGroupCreationKafkaProperties normalGroupCreationKafkaProperties() {
            return new NormalGroupCreationKafkaProperties();
        }

        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService(
                ProtocolCommandOutboxMapper mapper,
                ObjectMapper objectMapper,
                ProtocolCommandDispatchTrigger dispatchTrigger,
                ProtocolAccountCommandProperties accountProperties,
                ProtocolMasterCommandProperties masterProperties,
                ProtocolAndroidCommandProperties androidProperties,
                NormalGroupCreationKafkaProperties creationProperties) {
            return new ProtocolCommandOutboxServiceImpl(
                    mapper, objectMapper, dispatchTrigger, accountProperties,
                    masterProperties, androidProperties, creationProperties);
        }

        @Bean PullTaskManagerJoinResultService managerJoinResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskParentCompletionService completionService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskOperationDelayPolicy delayPolicy,
                GroupInviteLinkService inviteLinkService) {
            return new PullTaskManagerJoinResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, completionService, properties,
                    delayPolicy, inviteLinkService);
        }

        @Bean PullTaskManagerAdminResultService managerAdminResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskExecutionDispatchProperties properties,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskManagerAdminResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, properties, delayPolicy);
        }

        @Bean PullTaskGroupSettingsResultService groupSettingsResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                com.armada.account.service.AccountProtocolLookupService accountLookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskGroupSettingsResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, accountLookup,
                    outboxService, properties);
        }

        @Bean PullTaskContactSaveResultService contactSaveResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskContactSaveResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, delayPolicy);
        }

        @Bean PullTaskPullerInviteResultService pullerInviteResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskPullerInviteResultServiceImpl(
                    actionMapper, accountMapper, executionMapper);
        }

        @Bean PullTaskManagerPullerContactProcessor managerPullerContactProcessor(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper, PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper, AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskManagerPullerContactResources resources =
                    new PullTaskManagerPullerContactResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskManagerPullerContactTransactionService transactions =
                    new PullTaskManagerPullerContactTransactionService(
                            taskMapper, settingMapper, accountMapper, actionMapper, resources,
                            mock(PullTaskGroupProfileDispatcher.class));
            // 群设置改为异步命令后本阶段不再有事务外协议调用，无需再注入元数据与设置端口。
            return new PullTaskManagerPullerContactProcessor(
                    transactions,
                    mock(PullTaskSupplementPullerProcessor.class));
        }

        @Bean PullTaskPullerInviteProcessor pullerInviteProcessor(
                PullTaskMapper taskMapper, PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskPullerInviteResources resources =
                    new PullTaskPullerInviteResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskPullerInviteTransactionService transactions =
                    new PullTaskPullerInviteTransactionService(
                            taskMapper, accountMapper, actionMapper, resources);
            return new PullTaskPullerInviteProcessor(transactions);
        }

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stationSelection(
                PullTaskGroupAccountMapper accountMapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStationSelectionService(accountMapper, lookup);
        }

        @Bean PullTaskPullWavePlanningSelection wavePlanningSelection(
                PullTaskStationSelectionService stationSelection,
                PullTaskBatchSizeSelector sizeSelector) {
            return new PullTaskPullWavePlanningSelection(stationSelection, sizeSelector);
        }

        @Bean PullTaskPullWavePlanningResources wavePlanningResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskPullWavePlanningSelection selection) {
            return new PullTaskPullWavePlanningResources(
                    executionMapper, waveMapper, callMapper, attemptMapper, selection);
        }

        @Bean PullTaskPullWavePlanningTransactionService wavePlanningService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskPullWavePlanningResources resources) {
            return new PullTaskPullWavePlanningTransactionService(
                    taskMapper, settingMapper, materialMapper, accountMapper, resources);
        }

        @Bean PullTaskStickyPullerTransactionService stickyPullers(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStickyPullerTransactionService(
                    executionMapper, accountMapper, callMapper, attemptMapper, lookup);
        }

        @Bean PullTaskPullWaveSettlementResources settlementResources(
                PullTaskMapper taskMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskPullWaveSettlementResources(
                    taskMapper, executionMapper, waveMapper, attemptMapper, materialMapper);
        }

        @Bean PullTaskPullWaveSettlementTransactionService settlementService(
                PullTaskPullWaveSettlementResources resources,
                PullTaskPullWavePlanningTransactionService planning,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullWaveSettlementTransactionService(
                    resources, planning, properties,
                    mock(PullTaskGroupProfileDispatcher.class));
        }

        @Bean PullTaskPullerStationContactProcessor pullerStationContacts(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullerStationContactResources resources) {
            return new PullTaskPullerStationContactProcessor(
                    new PullTaskPullerStationContactTransactionService(
                            taskMapper, accountMapper, actionMapper, resources));
        }

        @Bean PullTaskPullerStationContactResources pullerStationContactResources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                PullTaskPullCallMapper callMapper,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullerStationContactResources(
                    executionMapper, lookup, callMapper, outboxService, properties);
        }

        @Bean PullTaskBatchAddPersistence batchPersistence(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper) {
            return new PullTaskBatchAddPersistence(
                    executionMapper, waveMapper, callMapper, attemptMapper);
        }

        @Bean PullTaskBatchAddResources batchResources(
                PullTaskBatchAddPersistence persistence,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskBatchAddResources(
                    persistence, lookup, outboxService, delayPolicy);
        }

        @Bean PullTaskBatchAddProcessor batchProcessor(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskBatchAddResources resources) {
            return new PullTaskBatchAddProcessor(new PullTaskBatchAddTransactionService(
                    taskMapper, settingMapper, accountMapper, materialMapper, resources));
        }

        @Bean PullTaskClosingTransactionService pullClosing(
                PullTaskMapper taskMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskParentCompletionService parentCompletion) {
            return new PullTaskClosingTransactionService(
                    taskMapper, executionMapper, accountMapper, settingMapper, parentCompletion,
                    org.mockito.Mockito.mock(com.armada.group.service.GroupFolderService.class));
        }

        @Bean PullTaskPullExecutionDispatchResources pullDispatchResources(
                PullTaskPullWavePlanningTransactionService waves,
                PullTaskStickyPullerTransactionService pullers,
                PullTaskPullWaveSettlementTransactionService settlement,
                PullTaskPullerStationContactProcessor contacts,
                PullTaskBatchAddProcessor batch) {
            return new PullTaskPullExecutionDispatchResources(
                    waves, pullers, settlement, contacts, batch);
        }

        @Bean PullTaskPullExecutionProcessor pullExecutionProcessor(
                PullTaskPullExecutionDispatchResources resources,
                PullTaskClosingTransactionService closing) {
            PullTaskCreatorLeaveProcessor creatorLeave = mock(PullTaskCreatorLeaveProcessor.class);
            when(creatorLeave.process(any(), any(), anyLong()))
                    .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
            return new PullTaskPullExecutionProcessor(
                    resources, creatorLeave, closing);
        }

        @Bean PullTaskUnknownResultResources unknownResultResources(
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper accountMapper) {
            return new PullTaskUnknownResultResources(
                    actionMapper, callMapper, attemptMapper,
                    materialMapper, accountMapper);
        }

        @Bean PullTaskProtocolResultCallbackService protocolResultCallbackService(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullCallParticipantResultService participantResultService,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskProtocolResultCallbackServiceImpl(
                    resources, executionMapper,
                    participantResultService, delayPolicy);
        }

        @Bean PullTaskPullCallParticipantResultService participantResultService(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullCallResultCoordination coordination,
                org.springframework.context.ApplicationEventPublisher eventPublisher) {
            return new PullTaskPullCallParticipantResultService(
                    resources, executionMapper,
                    mock(com.armada.account.service.AccountOperationRestrictionService.class),
                    coordination, eventPublisher);
        }

        @Bean PullTaskPullWaveProgressService pullWaveProgressService(
                PullTaskPullWaveMapper waveMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskPullWaveProgressService(waveMapper, executionMapper);
        }

        @Bean PullTaskPullCallResultCoordination pullCallResultCoordination(
                PullTaskStickyPullerTransactionService stickyPullers,
                PullTaskPullWaveProgressService waveProgress) {
            return new PullTaskPullCallResultCoordination(
                    stickyPullers,
                    mock(PullTaskGroupExecutionFailureService.class),
                    waveProgress);
        }

        @Bean PullTaskOperationDelayPolicy operationDelayPolicy() {
            return new PullTaskOperationDelayPolicy(() -> 4_000L);
        }

        @Bean PullTaskExecutionStageRouter stageRouter(
                PullTaskLinkValidationProcessor linkProcessor,
                PullTaskManagerJoinProcessor managerJoinProcessor,
                PullTaskManagerAdminProcessor managerAdminProcessor,
                PullTaskManagerPullerContactProcessor managerPullerContactProcessor,
                PullTaskPullerInviteProcessor pullerInviteProcessor,
                PullTaskPullExecutionProcessor pullExecutionProcessor,
                PullTaskMaterialAdminProcessor materialAdminProcessor,
                PullTaskGroupCreateProcessor groupCreateProcessor) {
            return new PullTaskExecutionStageRouter(
                    linkProcessor, managerJoinProcessor, managerAdminProcessor,
                    managerPullerContactProcessor,
                    pullerInviteProcessor, pullExecutionProcessor, materialAdminProcessor,
                    groupCreateProcessor);
        }

        @Bean PullTaskGroupCreateProcessor groupCreateProcessor() {
            return mock(PullTaskGroupCreateProcessor.class);
        }

        @Bean GroupExecutionAccountSelector promoterSelector() {
            GroupExecutionAccountSelector selector = mock(GroupExecutionAccountSelector.class);
            when(selector.findPullTaskAdminPromoterCandidates(
                    7L, GROUP_JID, 901L)).thenAnswer(invocation -> {
                        ProtocolAccountRef candidate = promoter();
                        return List.of(new GroupExecutionAccount(
                                candidate.armadaAccountId(), candidate.backend().name(),
                                candidate.protocolAccountId(), candidate.wsPhone(), true));
                    });
            return selector;
        }

        @Bean PullTaskManagerAdminProcessor managerAdminProcessor(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                GroupExecutionAccountSelector promoterSelector,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskMemberQueryAwaitService memberQueryAwaitService) {
            PullTaskManagerAdminResources resources = new PullTaskManagerAdminResources(
                    executionMapper, promoterSelector, outboxService, properties);
            PullTaskManagerAdminTransactionService transactions =
                    new PullTaskManagerAdminTransactionService(
                            taskMapper, accountMapper, actionMapper,
                            new PullTaskManagerAdminCandidateSelector(), resources);
            return new PullTaskManagerAdminProcessor(transactions, memberQueryAwaitService);
        }

        @Bean PullTaskMaterialAdminProcessor materialAdminProcessor(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskMaterialAdminResources resources =
                    new PullTaskMaterialAdminResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskMaterialAdminTransactionService transactions =
                    new PullTaskMaterialAdminTransactionService(
                            taskMapper, accountMapper, materialMapper, resources);
            return new PullTaskMaterialAdminProcessor(transactions);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            PullTaskExecutionDispatchProperties properties =
                    new PullTaskExecutionDispatchProperties();
            properties.setBatchSize(1);
            properties.setLeaseMs(500L);
            properties.setRetryDelayMs(1_000L);
            return properties;
        }

        @Bean PullTaskExecutionDispatchCoordinator coordinator(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskExecutionStageRouter router,
                PullTaskResourceRecoveryTransactionService resourceRecovery,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskExecutionDispatchCoordinator(
                    executionMapper, router, resourceRecovery, properties, "e2e-worker");
        }

        @Bean PullTaskResourceRecoveryTransactionService resourceRecovery(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                PullTaskStationSelectionService stationSelection) {
            PullTaskResourceRecoveryResources resources =
                    new PullTaskResourceRecoveryResources(
                            executionMapper, lookup, stationSelection,
                            mock(com.armada.group.service.GroupExecutionAccountSelector.class),
                            mock(PullTaskAccountActionMapper.class),
                            new PullTaskManagerAdminCandidateSelector());
            return new PullTaskResourceRecoveryTransactionService(
                    taskMapper, settingMapper, accountMapper, resources);
        }
    }
}
