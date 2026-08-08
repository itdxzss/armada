package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskContactSaveResultServiceImplTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskOperationDelayPolicy delayPolicy = delayPolicy();
    private final PullTaskContactSaveResultServiceImpl service =
            new PullTaskContactSaveResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, delayPolicy);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void lastSuccessfulContactAdvancesExecutionToPullerInvite() {
        PullTaskAccountAction current = action(PullTaskActionStatus.SUBMITTED);
        when(actionMapper.selectByCommandId("cmd-contact-1")).thenReturn(current);
        when(accountMapper.selectById(501L)).thenReturn(actor(901L));
        when(accountMapper.selectById(502L)).thenReturn(targetPuller());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(actionMapper.selectByExecutionAndType(11L, PullTaskAccountActionType.SAVE_CONTACT.code()))
                .thenReturn(List.of(action(PullTaskActionStatus.SUCCESS)));
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.apply(callback(PullTaskContactSaveOutcome.SUCCESS, false));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> fact =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(fact.capture());
        assertThat(fact.getValue().targetStatus()).isEqualTo(PullTaskActionStatus.SUCCESS.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULLER_INVITE.code());
        assertThat(executionChange.getValue().nextRunAt()).isEqualTo(9_000L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void explicitRetryableFailureIsTerminalAndWakesRemainingContactsWithoutRetry() {
        when(actionMapper.selectByCommandId("cmd-contact-1"))
                .thenReturn(action(PullTaskActionStatus.SUBMITTED));
        when(accountMapper.selectById(501L)).thenReturn(actor(901L));
        when(accountMapper.selectById(502L)).thenReturn(targetPuller());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(actionMapper.selectByExecutionAndType(11L, PullTaskAccountActionType.SAVE_CONTACT.code()))
                .thenReturn(List.of(
                        action(PullTaskActionStatus.FAILED),
                        otherAction(PullTaskActionStatus.PENDING)));
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.apply(callback(PullTaskContactSaveOutcome.FAILED, true));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> fact =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(fact.capture());
        assertThat(fact.getValue().targetStatus()).isEqualTo(PullTaskActionStatus.FAILED.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(executionChange.getValue().nextRunAt()).isEqualTo(9_000L);
    }

    @Test
    void ambiguousContactResultPersistsUnknownAndStillAdvancesAfterBothDirections() {
        when(actionMapper.selectByCommandId("cmd-contact-1"))
                .thenReturn(action(PullTaskActionStatus.SUBMITTED));
        when(accountMapper.selectById(501L)).thenReturn(actor(901L));
        when(accountMapper.selectById(502L)).thenReturn(targetPuller());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(actionMapper.selectByExecutionAndType(
                11L, PullTaskAccountActionType.SAVE_CONTACT.code()))
                .thenReturn(List.of(
                        action(PullTaskActionStatus.SUBMITTED),
                        otherAction(PullTaskActionStatus.SUCCESS)));
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.apply(callback(PullTaskContactSaveOutcome.UNKNOWN, true));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> fact =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(fact.capture());
        assertThat(fact.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULLER_INVITE.code());
    }

    @Test
    void mismatchedActorAccountRejectsEventWithoutWritingFacts() {
        when(actionMapper.selectByCommandId("cmd-contact-1"))
                .thenReturn(action(PullTaskActionStatus.SUBMITTED));
        when(accountMapper.selectById(501L)).thenReturn(actor(999L));
        when(accountMapper.selectById(502L)).thenReturn(targetPuller());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        boolean handled = service.apply(callback(PullTaskContactSaveOutcome.SUCCESS, false));

        assertThat(handled).isFalse();
        verify(actionMapper, never()).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void pullerStationContactWakesCurrentPullCallWithoutChangingStage() {
        PullTaskAccountAction action = stationAction(PullTaskActionStatus.SUBMITTED);
        when(actionMapper.selectByCommandId("cmd-station-contact-1")).thenReturn(action);
        when(accountMapper.selectById(502L)).thenReturn(account(
                502L, 902L, PullTaskGroupAccountRole.PULLER));
        when(accountMapper.selectById(603L)).thenReturn(account(
                603L, 911L, PullTaskGroupAccountRole.STATION));
        when(executionMapper.selectById(11L)).thenReturn(pullExecution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.apply(stationCallback());

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().expectedStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(executionChange.getValue().nextRunAt()).isEqualTo(9_000L);
    }

    @Test
    void duplicateTerminalContactCallbackIsACompleteNoOp() {
        when(actionMapper.selectByCommandId("cmd-contact-1"))
                .thenReturn(action(PullTaskActionStatus.SUCCESS));
        when(accountMapper.selectById(501L)).thenReturn(actor(901L));
        when(accountMapper.selectById(502L)).thenReturn(targetPuller());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        boolean handled = service.apply(callback(PullTaskContactSaveOutcome.SUCCESS, false));

        assertThat(handled).isTrue();
        verify(actionMapper, never()).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    private static PullTaskContactSaveCallback callback(
            PullTaskContactSaveOutcome outcome,
            boolean retryable) {
        return new PullTaskContactSaveCallback(
                7L, 100L, 11L, 601L, 901L, "manager-901", "cmd-contact-1", 1,
                outcome, "ACCOUNT_BUSY", "busy", retryable, 5_000L);
    }

    private static PullTaskContactSaveCallback stationCallback() {
        return new PullTaskContactSaveCallback(
                7L, 100L, 11L, 701L, 902L, "puller-902",
                "cmd-station-contact-1", 1,
                PullTaskContactSaveOutcome.SUCCESS, null, null, false, 5_000L);
    }

    private static PullTaskAccountAction action(PullTaskActionStatus status) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(601L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(502L);
        row.setActionType(PullTaskAccountActionType.SAVE_CONTACT.code());
        row.setActionStatus(status.code());
        row.setCommandId("cmd-contact-1");
        return row;
    }

    private static PullTaskAccountAction otherAction(PullTaskActionStatus status) {
        PullTaskAccountAction row = action(status);
        row.setId(602L);
        row.setActorGroupAccountId(502L);
        row.setTargetGroupAccountId(501L);
        row.setCommandId(null);
        return row;
    }

    private static PullTaskAccountAction stationAction(PullTaskActionStatus status) {
        PullTaskAccountAction row = action(status);
        row.setId(701L);
        row.setActorGroupAccountId(502L);
        row.setTargetGroupAccountId(603L);
        row.setCommandId("cmd-station-contact-1");
        return row;
    }

    private static PullTaskGroupAccount actor(long accountId) {
        return account(501L, accountId, PullTaskGroupAccountRole.MANAGER);
    }

    private static PullTaskGroupAccount targetPuller() {
        return account(502L, 902L, PullTaskGroupAccountRole.PULLER);
    }

    private static PullTaskGroupAccount account(
            long id, long accountId, PullTaskGroupAccountRole role) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setRoleType(role.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setVersion(3);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        return row;
    }

    private static PullTaskGroupExecution pullExecution() {
        PullTaskGroupExecution row = execution();
        row.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        return row;
    }

    private static PullTaskOperationDelayPolicy delayPolicy() {
        PullTaskOperationDelayPolicy policy = mock(PullTaskOperationDelayPolicy.class);
        when(policy.nextSideEffectAt(anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0, Long.class) + 4_000L);
        return policy;
    }
}
