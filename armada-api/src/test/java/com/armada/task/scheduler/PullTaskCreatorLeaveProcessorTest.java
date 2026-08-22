package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.model.enums.GroupCreatorLeaveStatus;
import com.armada.group.model.vo.GroupCreatorLeaveAccount;
import com.armada.group.model.vo.GroupCreatorLeavePlan;
import com.armada.group.service.GroupCreatorLeaveService;
import com.armada.platform.protocol.model.command.ProtocolPullTaskCreatorLeaveCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullTaskCreatorLeaveProcessorTest {

    @Mock private PullTaskStandardSettingMapper settingMapper;
    @Mock private PullTaskMapper taskMapper;
    @Mock private PullTaskGroupAccountMapper accountMapper;
    @Mock private PullTaskAccountActionMapper actionMapper;
    @Mock private PullTaskGroupExecutionMapper executionMapper;
    @Mock private GroupCreatorLeaveService creatorLeaveService;
    @Mock private ProtocolCommandOutboxService outboxService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void disabledSettingDoesNotExecuteCreatorLeave() {
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setCreatorLeaveAfterPull(0);
        when(settingMapper.selectByTaskId(100L)).thenReturn(setting);
        PullTaskGroupExecution candidate = candidate();

        assertThat(processor().process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(candidate.getCreatorLeaveResult()).isNull();
        verifyNoInteractions(taskMapper, accountMapper, actionMapper,
                creatorLeaveService, outboxService, executionMapper);
    }

    @Test
    void existingControlledAdminSkipsPromotionAndDispatchesDirectLeave() {
        arrangeEnabledTask(PullTaskCreationMode.PASTED_LINK);
        GroupCreatorLeaveAccount owner = owner();
        when(creatorLeaveService.plan(91L, null))
                .thenReturn(new GroupCreatorLeavePlan(owner, null, null));
        PullTaskGroupAccount ownerRole = role(501L, 81L);
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PROMOTER.code()))
                .thenReturn(List.of(ownerRole));
        PullTaskAccountAction leave = action(
                701L, PullTaskAccountActionType.CREATOR_LEAVE, 501L, 501L);
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.CREATOR_LEAVE.code()))
                .thenReturn(List.of(leave));
        when(outboxService.enqueuePullTaskCreatorLeaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd-leave-1"), 1));
        when(actionMapper.submitAttempt(eq(701L), anyList(), eq("cmd-leave-1"), eq(1_000L)))
                .thenReturn(1);
        when(executionMapper.transitionClaimed(any(), eq(PullTaskExecutionStage.CLOSING.code())))
                .thenReturn(1);

        assertThat(processor().process(candidate(), "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        ArgumentCaptor<List<ProtocolPullTaskCreatorLeaveCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskCreatorLeaveCommands(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(ProtocolPullTaskCreatorLeaveCommandRequest::action)
                .isEqualTo(ProtocolPullTaskCreatorLeaveCommandRequest.Action.LEAVE);
        verify(actionMapper, never()).selectByExecutionAndType(
                11L, PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR.code());
    }

    @Test
    void ordinaryControlledMemberIsPromotedBeforeAnyLeaveCommand() {
        arrangeEnabledTask(PullTaskCreationMode.PASTED_LINK);
        GroupCreatorLeaveAccount owner = owner();
        GroupCreatorLeaveAccount controller = new GroupCreatorLeaveAccount(
                82L, "android-zhuan", "controller-82", "919000000082",
                owner.groupJid(), "82@s.whatsapp.net", 1, 0, 0, 800L);
        when(creatorLeaveService.plan(91L, null))
                .thenReturn(new GroupCreatorLeavePlan(owner, controller, null));
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PROMOTER.code()))
                .thenReturn(List.of(role(501L, 81L)));
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.CONTROLLER.code()))
                .thenReturn(List.of(role(502L, 82L)));
        PullTaskAccountAction promote = action(
                702L, PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR, 501L, 502L);
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR.code()))
                .thenReturn(List.of(promote));
        when(outboxService.enqueuePullTaskCreatorLeaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd-promote-1"), 1));
        when(actionMapper.submitAttempt(eq(702L), anyList(), eq("cmd-promote-1"), eq(1_000L)))
                .thenReturn(1);
        when(executionMapper.transitionClaimed(any(), eq(PullTaskExecutionStage.CLOSING.code())))
                .thenReturn(1);

        assertThat(processor().process(candidate(), "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        ArgumentCaptor<List<ProtocolPullTaskCreatorLeaveCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskCreatorLeaveCommands(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(ProtocolPullTaskCreatorLeaveCommandRequest::action)
                .isEqualTo(ProtocolPullTaskCreatorLeaveCommandRequest.Action.PROMOTE);
        verify(actionMapper, never()).selectByExecutionAndType(
                11L, PullTaskAccountActionType.CREATOR_LEAVE.code());
    }

    @Test
    void noControlledAdminOrMemberDoesNotBlockCompletedPullTask() {
        arrangeEnabledTask(PullTaskCreationMode.PASTED_LINK);
        when(creatorLeaveService.plan(91L, null)).thenReturn(
                GroupCreatorLeavePlan.failed(GroupCreatorLeaveStatus.NO_AVAILABLE_CONTROLLER));
        PullTaskGroupExecution candidate = candidate();

        assertThat(processor().process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(candidate.getCreatorLeaveResult())
                .isEqualTo(GroupCreatorLeaveStatus.NO_AVAILABLE_CONTROLLER.code());
        assertThat(candidate.getCreatorLeaveReason()).contains("控端管理员");
        verifyNoInteractions(outboxService);
    }

    private void arrangeEnabledTask(PullTaskCreationMode creationMode) {
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setCreatorLeaveAfterPull(1);
        when(settingMapper.selectByTaskId(100L)).thenReturn(setting);
        PullTask parent = new PullTask();
        parent.setTaskType(PullTaskType.STANDARD);
        parent.setMode("NORMAL_LINK");
        parent.setStatus(PullTaskStandardStatus.EXECUTING.name());
        parent.setCreationMode(creationMode);
        when(taskMapper.selectLifecycle(100L)).thenReturn(parent);
    }

    private PullTaskCreatorLeaveProcessor processor() {
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setResultReconciliationDelayMs(60_000L);
        return new PullTaskCreatorLeaveProcessor(
                settingMapper, taskMapper, accountMapper, actionMapper, executionMapper,
                creatorLeaveService, outboxService, properties);
    }

    private static GroupCreatorLeaveAccount owner() {
        return new GroupCreatorLeaveAccount(
                81L, "android-zhuan", "owner-81", "919000000081",
                "120363000000000000@g.us", "81@s.whatsapp.net", 3, 1, 0, 500L);
    }

    private static PullTaskGroupAccount role(long id, long accountId) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setAccountId(accountId);
        return row;
    }

    private static PullTaskAccountAction action(
            long id,
            PullTaskAccountActionType type,
            long actorId,
            long targetId) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(id);
        row.setActionType(type.code());
        row.setActorGroupAccountId(actorId);
        row.setTargetGroupAccountId(targetId);
        row.setActionStatus(PullTaskActionStatus.PENDING.code());
        row.setAttemptNo(0);
        return row;
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupLinkId(91L);
        row.setGroupJid("120363000000000000@g.us");
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.CLOSING.code());
        row.setVersion(6);
        row.setLockOwner("worker-1");
        return row;
    }
}
