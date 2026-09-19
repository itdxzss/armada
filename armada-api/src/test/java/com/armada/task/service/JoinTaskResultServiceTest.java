package com.armada.task.service;

import com.armada.task.model.dto.JoinTaskRetryTransition;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.service.impl.JoinTaskResultServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinTaskResultServiceTest {

    @Mock
    private JoinTaskResultMapper resultMapper;
    @Mock
    private JoinTaskMapper taskMapper;
    @Mock
    private com.armada.task.mapper.JoinTaskApprovalMapper approvalMapper;
    @Mock
    private AccountGroupMembershipStatusService membershipStatusService;
    @Mock
    private MarketingNewGroupImmediateSendService marketingNewGroupService;

    private JoinTaskResultService service;

    @BeforeEach
    void setUp() {
        service = new JoinTaskResultServiceImpl(
                resultMapper,
                taskMapper,
                approvalMapper,
                new JoinTaskIntervalPolicy(),
                membershipStatusService,
                marketingNewGroupService,
                () -> 10_000L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void joinedWithAdminEnabledDoesNotAdvanceBeforePromotion() {
        JoinTask task = task(true, 2, 5);
        task.setSetAdminEnabled(true);
        stubSubmitted(task, row(1));
        service.apply(event("JOINED", null, false, "120363@g.us", 1));
        verify(resultMapper).markTerminalSuccess(26L, "120363@g.us", 10_000L, "cmd-1", 1);
        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(taskMapper, never()).markDoneWhenNoPending(anyLong(), anyLong());
        verify(taskMapper).refreshCounters(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void apply_joinedMarksSuccessAndSchedulesOnlyNextSameAccountRow() {
        stubSubmitted(task(true, 2, 5), row(1));

        service.apply(event("JOINED", null, false, "120363@g.us", 1));

        verify(resultMapper).markTerminalSuccess(26L, "120363@g.us", 10_000L, "cmd-1", 1);
        verify(membershipStatusService).applyMembershipChanged(
                new AccountGroupMembershipChangedEvent(
                        1L,
                        382L,
                        "acc-1",
                        "120363@g.us",
                        "add",
                        9_000L,
                        "event-1",
                        "JOIN_TASK_RESULT"));
        verify(marketingNewGroupService).enqueueDelayedNewGroups(
                382L,
                List.of(new MarketingNewGroupDTO(null, "120363@g.us", null)),
                10_000L);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
        verify(taskMapper).refreshCounters(9L);
        verify(taskMapper).markDoneWhenNoPending(9L, 10_000L);
    }

    @Test
    void apply_alreadyJoinedDoesNotRegisterNewGroupMarketing() {
        stubSubmitted(task(true, 2, 5), row(1));

        service.apply(event("ALREADY_JOINED", null, false, "120363@g.us", 1));

        verify(resultMapper).markTerminalSuccess(26L, "120363@g.us", 10_000L, "cmd-1", 1);
        verifyNoInteractions(membershipStatusService);
        verifyNoInteractions(marketingNewGroupService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-group", "@g.us", "120363@g.us@evil"})
    void apply_joinedWithInvalidGroupJidDoesNotFinalizeOrRegisterMarketing(String groupJid) {
        stubSubmitted(task(true, 2, 5), row(1));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.apply(event("JOINED", null, false, groupJid, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("进群成功结果 groupJid 非法");

        verify(resultMapper, never()).markTerminalSuccess(
                anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(resultMapper, never()).activateNextPending(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(membershipStatusService);
        verifyNoInteractions(marketingNewGroupService);
    }

    @Test
    void apply_joinedWithInvalidFactTimestampDoesNotOverwriteMembershipOrFinalize() {
        stubSubmitted(task(true, 2, 5), row(1));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.apply(eventWithTimestamp(
                                "JOINED", null, false, "120363@g.us", 1, 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("进群成功结果 timestamp 非法");

        verify(resultMapper, never()).markTerminalSuccess(
                anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(membershipStatusService);
        verifyNoInteractions(marketingNewGroupService);
    }

    @Test
    void apply_retryableFailureRetriesCurrentRowWithinConfiguredExtraRetries() {
        stubSubmitted(task(true, 2, 5), row(2));

        service.apply(event("FAILED", "TEMPORARY_FAILURE", true, null, 2));

        verify(resultMapper).markRetry(new JoinTaskRetryTransition(26L, "TEMPORARY_FAILURE", 15_000L, 10_000L, "cmd-1", 2));
        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(taskMapper, never()).refreshCounters(anyLong());
    }

    @Test
    void apply_retryExhaustedMarksFailureAndAdvances() {
        stubSubmitted(task(true, 2, 5), row(3));

        service.apply(event("FAILED", "RATE_LIMITED", true, null, 3));

        verify(resultMapper).markTerminalFailure(26L, "RATE_LIMITED", 10_000L, "cmd-1", 3);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP_BANNED", "GROUP_FULL", "GROUP_UNAVAILABLE", "INVITE_REVOKED"})
    void apply_preservesPermanentGroupFailureAndAdvancesWithoutRetry(String reason) {
        stubSubmitted(task(true, 2, 5), row(1));

        service.apply(event("FAILED", reason, false, null, 1));

        verify(resultMapper).markTerminalFailure(26L, reason, 10_000L, "cmd-1", 1);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
        verify(resultMapper, never()).markRetry(org.mockito.ArgumentMatchers.any(JoinTaskRetryTransition.class));
        verifyNoInteractions(membershipStatusService);
    }

    @Test
    void apply_pendingApprovalDoesNotFailOrResubmitJoin() {
        stubSubmitted(task(true, 5, 5), row(1));

        when(approvalMapper.begin(org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(1);
        when(approvalMapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        service.apply(event("PENDING_APPROVAL", "IGNORED", true, null, 1));
        verify(approvalMapper).insert(org.mockito.ArgumentMatchers.argThat(r ->
                r.getStage() == 1 && r.getResultId() == 26L && r.getDeadlineAt() == 310_000L));

        verify(resultMapper, never()).markTerminalFailure(26L, "JOIN_PENDING_APPROVAL", 10_000L, "cmd-1", 1);
        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(resultMapper, never()).markRetry(org.mockito.ArgumentMatchers.any(JoinTaskRetryTransition.class));
    }

    @Test
    void apply_duplicateOrStaleEventIsIdempotentAndRestoresTenantContext() {
        TenantContext.set(99L);
        when(resultMapper.selectSubmitted(26L, "cmd-1", 1)).thenReturn(null);

        service.apply(event("FAILED", "TEMPORARY_FAILURE", true, null, 1));

        verifyNoInteractions(taskMapper);
        verifyNoInteractions(membershipStatusService);
        verifyNoInteractions(marketingNewGroupService);
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void applyTransportFailure_retriesOnlyTheStillMatchingDeadAttempt() {
        JoinTaskResult row = row(2);
        when(resultMapper.selectSubmitted(26L, "cmd-dead", 2)).thenReturn(row);
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(true, 2, 5));

        service.applyTransportFailure(new JoinTaskDeadCommandCandidate(1L, 26L, "cmd-dead", 2));

        verify(resultMapper).markRetry(new JoinTaskRetryTransition(26L, "KAFKA_PUBLISH_FAILED", 15_000L, 10_000L, "cmd-dead", 2));
        verify(resultMapper, never()).markTerminalFailure(anyLong(),
                org.mockito.ArgumentMatchers.anyString(), anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private void stubSubmitted(JoinTask task, JoinTaskResult row) {
        when(resultMapper.selectSubmitted(26L, "cmd-1", row.getAttemptNo())).thenReturn(row);
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task);
        org.mockito.Mockito.lenient().when(resultMapper.markTerminalSuccess(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        org.mockito.Mockito.lenient().when(resultMapper.markTerminalFailure(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
    }

    @Test
    void competingResultThatLosesUpdateDoesNotApplyMembershipOrAdvanceTask() {
        stubSubmitted(task(true, 2, 5), row(1));
        when(resultMapper.markTerminalSuccess(26L, "120363@g.us", 10_000L, "cmd-1", 1)).thenReturn(0);

        service.apply(event("JOINED", null, false, "120363@g.us", 1));

        verifyNoInteractions(membershipStatusService, marketingNewGroupService);
        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(taskMapper, never()).refreshCounters(anyLong());
        verify(taskMapper, never()).markDoneWhenNoPending(anyLong(), anyLong());
    }

    @Test
    void competingFailureThatLosesUpdateDoesNotAdvanceTask() {
        stubSubmitted(task(false, 0, 5), row(1));
        when(resultMapper.markTerminalFailure(26L, "FAILED", 10_000L, "cmd-1", 1)).thenReturn(0);

        service.apply(event("FAILED", "FAILED", false, null, 1));

        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(taskMapper, never()).refreshCounters(anyLong());
    }

    private static JoinTask task(boolean retryEnabled, int retryLimit, int intervalSeconds) {
        JoinTask task = new JoinTask();
        task.setId(9L);
        task.setRetryEnabled(retryEnabled);
        task.setRetryLimit(retryLimit);
        task.setDistributionMode("FIXED_ACCOUNTS_PER_LINK");
        task.setFixedIntervalMinSec(intervalSeconds);
        task.setFixedIntervalMaxSec(intervalSeconds);
        return task;
    }

    private static JoinTaskResult row(int attemptNo) {
        JoinTaskResult row = new JoinTaskResult();
        row.setId(26L);
        row.setJoinTaskId(9L);
        row.setAccountId(382L);
        row.setAttemptNo(attemptNo);
        return row;
    }

    private static JoinTaskResultReportedEvent event(
            String outcome, String reason, boolean retryable, String groupJid, int attemptNo) {
        return eventWithTimestamp(outcome, reason, retryable, groupJid, attemptNo, 9_000L);
    }

    private static JoinTaskResultReportedEvent eventWithTimestamp(
            String outcome,
            String reason,
            boolean retryable,
            String groupJid,
            int attemptNo,
            long timestamp) {
        return new JoinTaskResultReportedEvent(
                "event-1", 1L, 9L, 26L, 382L, "acc-1", "cmd-1", attemptNo,
                outcome, groupJid, reason, null, retryable, timestamp, "worker-1");
    }
}
