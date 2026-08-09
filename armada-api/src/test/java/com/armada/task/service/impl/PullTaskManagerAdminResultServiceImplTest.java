package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskManagerAdminResultServiceImplTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskExecutionDispatchProperties properties = properties();
    private final PullTaskOperationDelayPolicy delayPolicy = delayPolicy();
    private final PullTaskManagerAdminResultServiceImpl service =
            new PullTaskManagerAdminResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, properties, delayPolicy);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void successConfirmsManagerWithoutRealtimeVerification() {
        stubContext(action());
        when(actionMapper.transitionManagerAdminResult(
                eq(711L), eq("cmd-promote-2"), eq(2), anyList(),
                eq(PullTaskActionStatus.SUCCESS.code()), eq(false),
                eq(null), eq(null), eq(5_000L))).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                eq(501L), anyList(), eq(PullTaskGroupAccountAdminStatus.SUCCESS.code()),
                eq(5_000L))).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.SUCCESS,
                null, false))).isTrue();

        verify(accountMapper).transitionAdminStatus(
                eq(501L), anyList(), eq(PullTaskGroupAccountAdminStatus.SUCCESS.code()), eq(5_000L));
        PullTaskManagerJoinResultTransition.Target target = capturedExecutionTarget();
        assertThat(target.stage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(target.reasonCode()).isNull();
        assertThat(target.nextRunAt()).isEqualTo(9_000L);
    }

    @Test
    void permissionDeniedIsStableAndRotatesImmediately() {
        stubContext(action());
        when(actionMapper.transitionManagerAdminResult(
                eq(711L), eq("cmd-promote-2"), eq(2), anyList(),
                eq(PullTaskActionStatus.FAILED.code()), eq(false),
                eq("GROUP_PERMISSION_DENIED"),
                eq("提权账号已无群管理员权限"), eq(5_000L))).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                eq(501L), anyList(), eq(PullTaskGroupAccountAdminStatus.PENDING.code()),
                eq(5_000L))).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.FAILED,
                "GROUP_PERMISSION_DENIED", false))).isTrue();

        PullTaskManagerJoinResultTransition.Target target = capturedExecutionTarget();
        assertThat(target.reasonCode()).isEqualTo(
                PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED.name());
        assertThat(target.reasonMessage()).isEqualTo("提权账号已无群管理员权限");
        assertThat(target.nextRunAt()).isEqualTo(9_000L);
    }

    @Test
    void rateLimitedIsRetryableAndUsesBackoff() {
        stubContext(action());
        when(actionMapper.transitionManagerAdminResult(
                eq(711L), eq("cmd-promote-2"), eq(2), anyList(),
                eq(PullTaskActionStatus.FAILED.code()), eq(true),
                eq("RATE_LIMITED"), eq("群操作触发限流，稍后重试"), eq(5_000L))).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                eq(501L), anyList(), eq(PullTaskGroupAccountAdminStatus.PENDING.code()),
                eq(5_000L))).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.FAILED,
                "RATE_LIMITED", true))).isTrue();

        PullTaskManagerJoinResultTransition.Target target = capturedExecutionTarget();
        assertThat(target.reasonCode()).isEqualTo(
                PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED.name());
        assertThat(target.nextRunAt()).isEqualTo(35_000L);
    }

    @Test
    void unknownSchedulesRealtimeVerification() {
        stubContext(action());
        when(actionMapper.transitionManagerAdminResult(
                eq(711L), eq("cmd-promote-2"), eq(2), anyList(),
                eq(PullTaskActionStatus.UNKNOWN.code()), eq(true),
                eq("WORKER_BUSY"), eq("提权账号当前繁忙，稍后重试"), eq(5_000L))).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                eq(501L), anyList(), eq(PullTaskGroupAccountAdminStatus.UNKNOWN.code()),
                eq(5_000L))).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.UNKNOWN,
                "WORKER_BUSY", true))).isTrue();

        PullTaskManagerJoinResultTransition.Target target = capturedExecutionTarget();
        assertThat(target.reasonCode()).isEqualTo(
                PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED.name());
        assertThat(target.nextRunAt()).isEqualTo(35_000L);
    }

    @Test
    void duplicateCallbackIsIdempotent() {
        PullTaskAccountAction terminal = action();
        terminal.setActionStatus(PullTaskActionStatus.SUCCESS.code());
        stubContext(terminal);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.SUCCESS,
                null, false))).isTrue();

        verify(actionMapper, never()).transitionManagerAdminResult(
                eq(711L), eq("cmd-promote-2"), eq(2), anyList(),
                eq(PullTaskActionStatus.SUCCESS.code()), eq(false),
                eq(null), eq(null), eq(5_000L));
        verify(executionMapper, never()).transitionManagerJoinResult(any());
    }

    @Test
    void lateOldCommandIdOrAttemptCannotOverwriteCurrentAttempt() {
        PullTaskAccountAction current = action();
        current.setCommandId("cmd-promote-3");
        current.setAttemptNo(3);
        when(actionMapper.selectByCommandId("cmd-promote-2")).thenReturn(current);

        assertThat(service.apply(callback(PullTaskManagerAdminProtocolOutcome.FAILED,
                "GROUP_PERMISSION_DENIED", false))).isFalse();

        verify(accountMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
        verify(actionMapper, never()).transitionManagerAdminResult(
                org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void targetMustBeTheFrozenManagerJid() {
        stubContext(action());
        PullTaskManagerAdminCallback mismatched = new PullTaskManagerAdminCallback(
                7L, 100L, 11L, 711L, 903L, "promoter-903", "cmd-promote-2", 2,
                "999@s.whatsapp.net", PullTaskManagerAdminProtocolOutcome.SUCCESS,
                null, null, false, 5_000L);

        assertThat(service.apply(mismatched)).isFalse();

        verify(actionMapper, never()).transitionManagerAdminResult(
                org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private PullTaskManagerJoinResultTransition.Target capturedExecutionTarget() {
        ArgumentCaptor<PullTaskManagerJoinResultTransition> captor =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(captor.capture());
        return captor.getValue().target();
    }

    private void stubContext(PullTaskAccountAction action) {
        when(actionMapper.selectByCommandId("cmd-promote-2")).thenReturn(action);
        when(accountMapper.selectById(503L)).thenReturn(account(
                503L, 903L, "903", PullTaskGroupAccountRole.PROMOTER,
                PullTaskGroupAccountAdminStatus.SUCCESS));
        when(accountMapper.selectById(501L)).thenReturn(account(
                501L, 15L, "15", PullTaskGroupAccountRole.MANAGER,
                PullTaskGroupAccountAdminStatus.SUBMITTED));
        when(executionMapper.selectById(11L)).thenReturn(execution());
    }

    private static PullTaskManagerAdminCallback callback(
            PullTaskManagerAdminProtocolOutcome outcome,
            String reasonCode,
            boolean retryable) {
        return new PullTaskManagerAdminCallback(
                7L, 100L, 11L, 711L, 903L, "promoter-903", "cmd-promote-2", 2,
                "15@s.whatsapp.net", outcome, reasonCode, "raw protocol error",
                retryable, 5_000L);
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(711L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActorGroupAccountId(503L);
        row.setTargetGroupAccountId(501L);
        row.setActionType(PullTaskAccountActionType.PROMOTE_MANAGER.code());
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-promote-2");
        row.setAttemptNo(2);
        return row;
    }

    private static PullTaskGroupAccount account(
            long id,
            long accountId,
            String phone,
            PullTaskGroupAccountRole role,
            PullTaskGroupAccountAdminStatus adminStatus) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
        row.setAdminStatus(adminStatus.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setVersion(4);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.MANAGER_ADMIN.code());
        return row;
    }

    private static PullTaskExecutionDispatchProperties properties() {
        PullTaskExecutionDispatchProperties result = new PullTaskExecutionDispatchProperties();
        result.setRetryDelayMs(30_000L);
        return result;
    }

    private static PullTaskOperationDelayPolicy delayPolicy() {
        PullTaskOperationDelayPolicy policy = mock(PullTaskOperationDelayPolicy.class);
        when(policy.maxDeadline(anyLong(), anyLong())).thenAnswer(invocation -> Math.max(
                invocation.getArgument(0, Long.class),
                invocation.getArgument(1, Long.class) + 4_000L));
        return policy;
    }
}
