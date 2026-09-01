package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskManagerJoinTransactionServiceTest {

    private static final long NOW = 1_000L;

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskStandardSettingMapper settingMapper =
            mock(PullTaskStandardSettingMapper.class);
    private final PullTaskGroupAccountMapper groupAccountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final AccountProtocolLookupService accountLookup =
            mock(AccountProtocolLookupService.class);
    private final PullTaskParentCompletionService parentCompletionService =
            mock(PullTaskParentCompletionService.class);
    private final ProtocolCommandOutboxService outboxService =
            mock(ProtocolCommandOutboxService.class);
    private final PullTaskExecutionDispatchProperties properties =
            new PullTaskExecutionDispatchProperties();
    private final PullTaskManagerJoinTransactionService service =
            new PullTaskManagerJoinTransactionService(taskMapper, settingMapper,
                    groupAccountMapper, actionMapper,
                    new PullTaskManagerJoinResources(
                            executionMapper, accountLookup, parentCompletionService,
                            outboxService, properties));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void selectsExactlyOneManagerAndPersistsSubmittedJoinAction() {
        PullTaskGroupExecution candidate = candidate();
        seedDispatchableParent(candidate);
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1)).thenReturn(List.of());
        when(accountLookup.findRandomOnlineNormalPullerByGroupId(88L))
                .thenReturn(Optional.of(account()));
        doAnswer(invocation -> {
            invocation.<PullTaskGroupAccount>getArgument(0).setId(501L);
            return 1;
        }).when(groupAccountMapper).insert(any(PullTaskGroupAccount.class));
        doAnswer(invocation -> {
            invocation.<PullTaskAccountAction>getArgument(0).setId(601L);
            return 1;
        }).when(actionMapper).insertIfAbsent(any(PullTaskAccountAction.class));
        when(outboxService.enqueuePullTaskGroupJoinCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-pull-601"), 1));
        when(actionMapper.markSubmitted(601L, "cmd-pull-601", NOW)).thenReturn(1);
        when(groupAccountMapper.updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.JOINING.code(), null, NOW)).thenReturn(1);
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        ArgumentCaptor<PullTaskGroupAccount> accountRow =
                ArgumentCaptor.forClass(PullTaskGroupAccount.class);
        verify(groupAccountMapper).insert(accountRow.capture());
        assertThat(accountRow.getValue().getAccountId()).isEqualTo(901L);
        assertThat(accountRow.getValue().getRoleSeq()).isEqualTo(1);

        ArgumentCaptor<PullTaskAccountAction> actionRow =
                ArgumentCaptor.forClass(PullTaskAccountAction.class);
        verify(actionMapper).insertIfAbsent(actionRow.capture());
        assertThat(actionRow.getValue().getActorGroupAccountId()).isEqualTo(501L);
        assertThat(actionRow.getValue().getTargetGroupAccountId()).isEqualTo(501L);
        ArgumentCaptor<List<ProtocolPullTaskGroupJoinCommandRequest>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupJoinCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.tenantId()).isEqualTo(7L);
            assertThat(command.pullTaskId()).isEqualTo(100L);
            assertThat(command.groupExecutionId()).isEqualTo(11L);
            assertThat(command.actionId()).isEqualTo(601L);
            assertThat(command.account()).isEqualTo(account());
        });
        verify(actionMapper).markSubmitted(601L, "cmd-pull-601", NOW);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void noOnlineManagerMovesOnlyThisExecutionRowToResourceWaiting() {
        PullTaskGroupExecution candidate = candidate();
        seedDispatchableParent(candidate);
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1)).thenReturn(List.of());
        when(accountLookup.findRandomOnlineNormalPullerByGroupId(88L))
                .thenReturn(Optional.empty());
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(update.getValue().getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.MANAGER.code());
        assertThat(update.getValue().getReasonCode()).isEqualTo("MANAGER_UNAVAILABLE");
    }

    @Test
    void confirmedMembershipAdvancesToManagerPullerContactAtomically() {
        PullTaskManagerJoinWork work = work();
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);
        when(actionMapper.writeBackResult(601L, PullTaskActionStatus.SUCCESS.code(),
                null, null, NOW)).thenReturn(1);
        when(groupAccountMapper.updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), NOW, NOW)).thenReturn(1);

        PullTaskExecutionDispatchResult result = service.complete(
                work,
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"),
                NOW);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(update.getValue().getGroupJid()).isEqualTo("120363group@g.us");
        verify(actionMapper).writeBackResult(601L, PullTaskActionStatus.SUCCESS.code(),
                null, null, NOW);
        verify(groupAccountMapper).updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), NOW, NOW);
    }

    @Test
    void managerAlreadyInNewlyCreatedGroupAdvancesWithoutSubmittingJoinCommand() {
        PullTaskGroupExecution candidate = candidate();
        candidate.setGroupJid("120363group@g.us");
        seedDispatchableParent(candidate);
        PullTaskGroupAccount manager = manager();
        manager.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1))
                .thenReturn(List.of(manager));
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<PullTaskAccountAction>getArgument(0).setId(601L);
            return 1;
        }).when(actionMapper).insertIfAbsent(any(PullTaskAccountAction.class));
        when(accountLookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(account()));
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);
        when(actionMapper.writeBackResult(601L, PullTaskActionStatus.SUCCESS.code(),
                null, null, NOW)).thenReturn(1);
        when(groupAccountMapper.updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), NOW, NOW)).thenReturn(1);

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.result()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        verify(outboxService, never()).enqueuePullTaskGroupJoinCommands(any());
    }

    @Test
    void pendingApprovalPausesOnlyThisGroupWithoutSchedulingAnotherJoin() {
        PullTaskManagerJoinWork work = work();
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);
        when(actionMapper.writeBackResult(601L, PullTaskActionStatus.PENDING_APPROVAL.code(),
                "MANAGER_JOIN_PENDING_APPROVAL",
                "管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停", NOW)).thenReturn(1);
        when(groupAccountMapper.updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code(), null, NOW))
                .thenReturn(1);

        PullTaskExecutionDispatchResult result = service.complete(
                work, PullTaskManagerJoinOutcome.pendingApproval("120363group@g.us"), NOW);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(update.getValue().getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.APPROVAL.code());
        assertThat(update.getValue().getReasonCode())
                .isEqualTo("MANAGER_JOIN_PENDING_APPROVAL");
        assertThat(update.getValue().getReasonMessage())
                .isEqualTo("管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停");
        assertThat(update.getValue().getNextRunAt()).isZero();
    }

    @Test
    void submittedWebJoinIsRecoveredWithFullLinkAndTheSameOperationId() {
        PullTaskGroupExecution candidate = candidate();
        candidate.setGroupJid("120363group@g.us");
        seedDispatchableParent(candidate);
        PullTaskGroupAccount manager = manager();
        PullTaskAccountAction action = submittedAction();
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1))
                .thenReturn(List.of(manager));
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .thenReturn(List.of(action));
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of(account()));

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.work().payload().operationId())
                .isEqualTo("pull-task-manager-join:601");
        assertThat(prepared.work().payload().inviteLink())
                .isEqualTo("https://chat.whatsapp.com/AAAA");
        assertThat(prepared.work().payload().knownGroupJid())
                .isEqualTo("120363group@g.us");
    }

    @Test
    void submittedAndroidJoinIsRecoveredWithPureInviteCode() {
        PullTaskGroupExecution candidate = candidate();
        seedDispatchableParent(candidate);
        PullTaskGroupAccount manager = manager();
        PullTaskAccountAction action = submittedAction();
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1))
                .thenReturn(List.of(manager));
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .thenReturn(List.of(action));
        when(accountLookup.findOnlineProtocolRefs(List.of(901L)))
                .thenReturn(List.of(androidAccount()));

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.work().payload().inviteLink()).isEqualTo("AAAA");
    }

    @Test
    void offlineManagerParksTheRowInsteadOfHandingAnOfflineAccountToTheJoinProtocol() {
        PullTaskGroupExecution candidate = candidate();
        seedDispatchableParent(candidate);
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1))
                .thenReturn(List.of(manager()));
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .thenReturn(List.of(submittedAction()));
        // 账号在库里仍然活着，只是已经离线；恢复踩链接必须放弃本轮，不能把离线号交给协议层。
        when(accountLookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(account()));
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of());
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(update.getValue().getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.MANAGER.code());
        assertThat(update.getValue().getReasonCode()).isEqualTo("MANAGER_UNAVAILABLE");
    }

    @Test
    void unknownJoinIsReopenedBeforeMembershipVerification() {
        PullTaskGroupExecution candidate = candidate();
        candidate.setGroupJid("120363group@g.us");
        seedDispatchableParent(candidate);
        PullTaskGroupAccount manager = manager();
        PullTaskAccountAction action = submittedAction();
        action.setActionStatus(PullTaskActionStatus.UNKNOWN.code());
        when(groupAccountMapper.selectByExecutionAndRole(11L, 1))
                .thenReturn(List.of(manager));
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .thenReturn(List.of(action));
        when(actionMapper.reopenForVerification(
                601L, PullTaskActionStatus.UNKNOWN.code(),
                PullTaskActionStatus.SUBMITTED.code(), NOW)).thenReturn(1);
        when(accountLookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of(account()));

        PullTaskManagerJoinPreparation prepared =
                service.prepare(candidate, "worker-1", NOW);

        assertThat(prepared.ready()).isTrue();
        verify(actionMapper).reopenForVerification(
                601L, PullTaskActionStatus.UNKNOWN.code(),
                PullTaskActionStatus.SUBMITTED.code(), NOW);
    }

    @Test
    void revokedLinkFailsTheExecutionRowWithoutBlamingTheSelectedAccount() {
        PullTaskManagerJoinWork work = work();
        when(executionMapper.transitionClaimed(any(PullTaskGroupExecution.class),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()))).thenReturn(1);
        when(actionMapper.writeBackResult(601L, PullTaskActionStatus.FAILED.code(),
                "INVITE_REVOKED", "群邀请链接已失效", NOW)).thenReturn(1);
        when(groupAccountMapper.updateMembership(501L,
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(), null, NOW)).thenReturn(1);

        PullTaskExecutionDispatchResult result = service.complete(
                work, PullTaskManagerJoinOutcome.executionFailed("INVITE_REVOKED"), NOW);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.FAILED);
        ArgumentCaptor<PullTaskGroupExecution> update =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(update.capture(),
                eq(PullTaskExecutionStage.MANAGER_JOIN.code()));
        assertThat(update.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.FAILED.code());
        assertThat(update.getValue().getFinishedAt()).isEqualTo(NOW);
        assertThat(update.getValue().getWaitResourceType()).isNull();
        verify(parentCompletionService).completeIfTerminalByExecutionId(11L, NOW);
    }

    private void seedDispatchableParent(PullTaskGroupExecution candidate) {
        PullTask parent = new PullTask();
        parent.setId(candidate.getTaskId());
        parent.setTaskType(PullTaskType.STANDARD);
        parent.setMode("NORMAL_LINK");
        parent.setStatus(PullTaskStandardStatus.EXECUTING.name());
        when(taskMapper.selectLifecycle(candidate.getTaskId())).thenReturn(parent);
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setManagerGroupId(88L);
        when(settingMapper.selectByTaskId(candidate.getTaskId())).thenReturn(setting);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setInviteCode("AAAA");
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        row.setVersion(2);
        row.setLockOwner("worker-1");
        return row;
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(901L, ProtocolBackend.WEB, "acc-901", "8613800000901");
    }

    private static ProtocolAccountRef androidAccount() {
        return new ProtocolAccountRef(
                901L, ProtocolBackend.ANDROID, "acc-901", "8613800000901");
    }

    private static PullTaskManagerJoinWork work() {
        return new PullTaskManagerJoinWork(7L, 11L, 501L, 601L,
                new PullTaskManagerJoinPayload(account(), "chat.whatsapp.com/AAAA",
                        "pull-task-manager-join:601", "worker-1", 2));
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setAccountId(901L);
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.JOINING.code());
        return row;
    }

    private static PullTaskAccountAction submittedAction() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(601L);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        return row;
    }
}
