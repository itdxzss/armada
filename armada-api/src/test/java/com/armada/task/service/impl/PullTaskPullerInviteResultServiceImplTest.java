package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskPullerInviteResultServiceImplTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskPullerInviteResultServiceImpl service =
            new PullTaskPullerInviteResultServiceImpl(actionMapper, accountMapper, executionMapper);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void successWritesActionAndMembershipThenWaitsThreeSecondsAfterResult() {
        stubContext();
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.apply(callback(PullTaskPullerInviteProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> actionChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(actionChange.capture());
        assertThat(actionChange.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        ArgumentCaptor<PullTaskFactTransition> membershipChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(accountMapper).transitionMembership(membershipChange.capture());
        assertThat(membershipChange.getValue().targetStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULLER_INVITE.code());
        assertThat(executionChange.getValue().nextRunAt()).isEqualTo(4_100L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void unknownWritesUnknownFactsWithoutAutomaticRetry() {
        stubContext();
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        assertThat(service.apply(callback(PullTaskPullerInviteProtocolOutcome.UNKNOWN))).isTrue();

        ArgumentCaptor<PullTaskFactTransition> actionChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(actionChange.capture());
        assertThat(actionChange.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        ArgumentCaptor<PullTaskFactTransition> membershipChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(accountMapper).transitionMembership(membershipChange.capture());
        assertThat(membershipChange.getValue().targetStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    }

    @Test
    void partialFactWriteThrowsForTransactionRollback() {
        stubContext();
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(callback(PullTaskPullerInviteProtocolOutcome.FAILED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拉手邀请事实");
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void factWriteWithLostWakeCasThrowsForTransactionRollback() {
        stubContext();
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(callback(PullTaskPullerInviteProtocolOutcome.SUCCESS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("执行行唤醒");
    }

    @Test
    void duplicateTerminalCallbackIsACompleteNoOp() {
        PullTaskAccountAction terminalAction = action();
        terminalAction.setActionStatus(PullTaskActionStatus.SUCCESS.code());
        PullTaskGroupAccount terminalTarget = account(502L, 902L, "8613800000902");
        terminalTarget.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        when(actionMapper.selectByCommandId("cmd-invite-1")).thenReturn(terminalAction);
        when(accountMapper.selectById(501L)).thenReturn(account(
                501L, 901L, "8613800000901"));
        when(accountMapper.selectById(502L)).thenReturn(terminalTarget);
        when(executionMapper.selectById(11L)).thenReturn(execution());

        boolean handled = service.apply(callback(PullTaskPullerInviteProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        verify(actionMapper, never()).transitionResult(any());
        verify(accountMapper, never()).transitionMembership(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    private void stubContext() {
        when(actionMapper.selectByCommandId("cmd-invite-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(account(501L, 901L, "8613800000901"));
        when(accountMapper.selectById(502L)).thenReturn(account(502L, 902L, "8613800000902"));
        when(executionMapper.selectById(11L)).thenReturn(execution());
    }

    private static PullTaskPullerInviteCallback callback(
            PullTaskPullerInviteProtocolOutcome outcome) {
        return new PullTaskPullerInviteCallback(
                7L, 100L, 11L, 701L, 901L, "manager-901", "cmd-invite-1", 1,
                "8613800000902@s.whatsapp.net", outcome,
                "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 1_100L);
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(701L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(502L);
        row.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-invite-1");
        row.setSubmittedAt(1_000L);
        return row;
    }

    private static PullTaskGroupAccount account(long id, long accountId, String phone) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setMembershipStatus(id == 502L
                ? PullTaskGroupAccountMembershipStatus.JOINING.code()
                : PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setVersion(3);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        return row;
    }
}
