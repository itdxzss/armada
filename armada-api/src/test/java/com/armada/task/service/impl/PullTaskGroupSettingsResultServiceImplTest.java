package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskGroupSettingsResultServiceImplTest {

    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final ProtocolCommandOutboxService outboxService =
            mock(ProtocolCommandOutboxService.class);
    private final PullTaskExecutionDispatchProperties properties =
            new PullTaskExecutionDispatchProperties();

    private final PullTaskGroupSettingsResultServiceImpl service =
            new PullTaskGroupSettingsResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, outboxService, properties);

    // ---------- 放开加人权限：推进条件 ----------

    @Test
    @DisplayName("加人权限成功后追加关闭审核命令并唤醒执行行")
    void memberAddSuccessEnqueuesJoinApprovalAndWakesExecution() {
        givenAction(PullTaskAccountActionType.OPEN_MEMBER_ADD);
        givenManagerAndExecution(PullTaskExecutionStage.MANAGER_PULLER_CONTACT);
        givenActionCasSucceeds();
        when(actionMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, PullTaskAccountAction.class).setId(812L);
            return 1;
        });
        when(outboxService.enqueuePullTaskGroupSettingsCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-close-1"), 1));
        when(actionMapper.markSubmitted(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isTrue();

        ArgumentCaptor<PullTaskAccountAction> inserted =
                ArgumentCaptor.forClass(PullTaskAccountAction.class);
        verify(actionMapper).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().getActionType())
                .isEqualTo(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code());
        // 群设置没有对象账号，actor 与 target 同为管理员角色行本身。
        assertThat(inserted.getValue().getActorGroupAccountId()).isEqualTo(501L);
        assertThat(inserted.getValue().getTargetGroupAccountId()).isEqualTo(501L);
        verify(outboxService).enqueuePullTaskGroupSettingsCommands(anyList());
        // 动作行必须写回真实 commandId，否则关闭审核的回调找不到自己的动作行。
        verify(actionMapper).markSubmitted(eq(812L), eq("cmd-close-1"), anyLong());
        verify(executionMapper).transitionManagerJoinResult(any());
    }

    @Test
    @DisplayName("加人权限被拒时使用权限不足原因码并退避，不发关闭审核命令")
    void memberAddPermissionDeniedBacksOffWithoutJoinApprovalCommand() {
        givenAction(PullTaskAccountActionType.OPEN_MEMBER_ADD);
        givenManagerAndExecution(PullTaskExecutionStage.MANAGER_PULLER_CONTACT);
        givenActionCasSucceeds();
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.FAILED, "GROUP_PERMISSION_DENIED")))
                .isTrue();

        // 权限没放开就发关闭审核，等于用一个必然失败的命令烧掉唯一一次机会。
        verify(outboxService, never()).enqueuePullTaskGroupSettingsCommands(anyList());
        verify(actionMapper, never()).insertIfAbsent(any());
        assertThat(capturedExecutionTarget().reasonCode())
                .isEqualTo(PullTaskExecutionReasonCode
                        .GROUP_MEMBER_ADD_PERMISSION_DENIED.name());
    }

    @Test
    @DisplayName("加人权限其它失败使用未确认原因码并退避")
    void memberAddOtherFailureUsesUnconfirmedReason() {
        givenAction(PullTaskAccountActionType.OPEN_MEMBER_ADD);
        givenManagerAndExecution(PullTaskExecutionStage.MANAGER_PULLER_CONTACT);
        givenActionCasSucceeds();
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.FAILED, "RATE_LIMITED"))).isTrue();

        assertThat(capturedExecutionTarget().reasonCode())
                .isEqualTo(PullTaskExecutionReasonCode
                        .GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED.name());
    }

    @Test
    @DisplayName("加人权限失败后保留当前阶段等待重试")
    void memberAddFailureKeepsContactStage() {
        givenAction(PullTaskAccountActionType.OPEN_MEMBER_ADD);
        givenManagerAndExecution(PullTaskExecutionStage.MANAGER_PULLER_CONTACT);
        givenActionCasSucceeds();
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        service.apply(callback(PullTaskGroupSettingsProtocolOutcome.FAILED, "RATE_LIMITED"));

        assertThat(capturedExecutionTarget().stage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
    }

    // ---------- 关闭进群审核：绝不触碰执行行 ----------

    @Test
    @DisplayName("关闭审核成功只写动作行，不触碰执行行")
    void joinApprovalSuccessNeverTouchesExecution() {
        givenAction(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL);
        givenActionCasSucceeds();

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isTrue();

        verifyNoInteractions(executionMapper);
    }

    @Test
    @DisplayName("关闭审核失败写留痕原因码，不阻断执行行也不重试")
    void joinApprovalFailureRecordsReasonWithoutBlocking() {
        givenAction(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL);
        givenActionCasSucceeds();

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.FAILED, "GROUP_PERMISSION_DENIED")))
                .isTrue();

        verify(actionMapper).transitionManagerAdminResult(
                eq(812L), eq("cmd-settings-1"), eq(2), anyList(),
                eq(PullTaskActionStatus.FAILED.code()),
                eq(false),
                eq(PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_CLOSE_FAILED.name()),
                anyString(), anyLong());
        verifyNoInteractions(executionMapper);
        verify(outboxService, never()).enqueuePullTaskGroupSettingsCommands(anyList());
    }

    @Test
    @DisplayName("执行行已推进到后续阶段时关闭审核结果仍能落库")
    void joinApprovalResultLandsAfterExecutionAdvanced() {
        givenAction(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL);
        givenActionCasSucceeds();
        // 执行行早已推进到 PULLER_INVITE，本分支不读也不 CAS 执行行，因此不受影响。
        givenManagerAndExecution(PullTaskExecutionStage.PULLER_INVITE);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isTrue();

        verify(actionMapper).transitionManagerAdminResult(
                eq(812L), eq("cmd-settings-1"), eq(2), anyList(),
                eq(PullTaskActionStatus.SUCCESS.code()),
                eq(false), eq(null), eq(null), anyLong());
    }

    // ---------- 幂等与关联校验 ----------

    @Test
    @DisplayName("尝试序号不匹配的回调被拒绝")
    void mismatchedAttemptNoIsRejected() {
        PullTaskAccountAction action = action(PullTaskAccountActionType.OPEN_MEMBER_ADD);
        action.setAttemptNo(3);
        when(actionMapper.selectByCommandId("cmd-settings-1")).thenReturn(action);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isFalse();

        verifyNoInteractions(executionMapper);
    }

    @Test
    @DisplayName("动作类型不是群设置的回调被拒绝")
    void nonGroupSettingsActionIsRejected() {
        givenAction(PullTaskAccountActionType.PROMOTE_MANAGER);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isFalse();

        verifyNoInteractions(executionMapper);
    }

    @Test
    @DisplayName("动作行已是目标终态时重复回调幂等返回成功")
    void repeatedCallbackOnTerminalActionIsIdempotent() {
        PullTaskAccountAction action = action(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL);
        action.setActionStatus(PullTaskActionStatus.SUCCESS.code());
        when(actionMapper.selectByCommandId("cmd-settings-1")).thenReturn(action);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isTrue();

        verify(actionMapper, never()).transitionManagerAdminResult(
                anyLong(), anyString(), anyInt(), anyList(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("找不到动作行的回调被拒绝")
    void unknownCommandIsRejected() {
        when(actionMapper.selectByCommandId("cmd-settings-1")).thenReturn(null);

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isFalse();
    }

    // ---------- fixtures ----------

    private void givenAction(PullTaskAccountActionType type) {
        when(actionMapper.selectByCommandId("cmd-settings-1")).thenReturn(action(type));
    }

    private void givenActionCasSucceeds() {
        when(actionMapper.transitionManagerAdminResult(
                anyLong(), anyString(), anyInt(), anyList(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyLong()))
                .thenReturn(1);
    }

    private void givenManagerAndExecution(PullTaskExecutionStage stage) {
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution(stage));
    }

    private PullTaskManagerJoinResultTransition.Target capturedExecutionTarget() {
        ArgumentCaptor<PullTaskManagerJoinResultTransition> captor =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(captor.capture());
        return captor.getValue().target();
    }

    private static PullTaskGroupSettingsCallback callback(
            PullTaskGroupSettingsProtocolOutcome outcome, String reasonCode) {
        return new PullTaskGroupSettingsCallback(
                7L, 100L, 11L, 812L, 901L, "manager-901", "WEB",
                "cmd-settings-1", 2, outcome, reasonCode, "raw", 1_000L);
    }

    private static PullTaskAccountAction action(PullTaskAccountActionType type) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(812L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(type.code());
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-settings-1");
        row.setAttemptNo(2);
        return row;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        return row;
    }

    private static PullTaskGroupExecution execution(PullTaskExecutionStage stage) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(stage.code());
        row.setGroupJid("120363group@g.us");
        row.setVersion(4);
        row.setLockOwner("worker-1");
        return row;
    }
}
