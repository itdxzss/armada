package com.armada.task.service;

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
    private MarketingNewGroupImmediateSendService marketingNewGroupService;

    private JoinTaskResultService service;

    @BeforeEach
    void setUp() {
        service = new JoinTaskResultServiceImpl(
                resultMapper,
                taskMapper,
                new JoinTaskIntervalPolicy(),
                marketingNewGroupService,
                () -> 10_000L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void apply_joinedMarksSuccessAndSchedulesOnlyNextSameAccountRow() {
        stubSubmitted(task(true, 2, 5), row(1));

        service.apply(event("JOINED", null, false, "120363@g.us", 1));

        verify(resultMapper).markTerminalSuccess(26L, "120363@g.us", 10_000L);
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

        verify(resultMapper).markTerminalSuccess(26L, "120363@g.us", 10_000L);
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
                anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
        verify(resultMapper, never()).activateNextPending(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(marketingNewGroupService);
    }

    @Test
    void apply_retryableFailureRetriesCurrentRowWithinConfiguredExtraRetries() {
        stubSubmitted(task(true, 2, 5), row(2));

        service.apply(event("FAILED", "TEMPORARY_FAILURE", true, null, 2));

        verify(resultMapper).markRetry(26L, "TEMPORARY_FAILURE", 15_000L, 10_000L);
        verify(resultMapper, never()).activateNextPending(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(taskMapper, never()).refreshCounters(anyLong());
    }

    @Test
    void apply_retryExhaustedMarksFailureAndAdvances() {
        stubSubmitted(task(true, 2, 5), row(3));

        service.apply(event("FAILED", "RATE_LIMITED", true, null, 3));

        verify(resultMapper).markTerminalFailure(26L, "RATE_LIMITED", 10_000L);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
    }

    @Test
    void apply_pendingApprovalIsTerminalEvenWhenMarkedRetryable() {
        stubSubmitted(task(true, 5, 5), row(1));

        service.apply(event("PENDING_APPROVAL", "IGNORED", true, null, 1));

        verify(resultMapper).markTerminalFailure(26L, "JOIN_PENDING_APPROVAL", 10_000L);
        verify(resultMapper, never()).markRetry(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                anyLong(), anyLong());
    }

    @Test
    void apply_duplicateOrStaleEventIsIdempotentAndRestoresTenantContext() {
        TenantContext.set(99L);
        when(resultMapper.selectSubmittedForUpdate(26L, "cmd-1", 1)).thenReturn(null);

        service.apply(event("FAILED", "TEMPORARY_FAILURE", true, null, 1));

        verifyNoInteractions(taskMapper);
        verifyNoInteractions(marketingNewGroupService);
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void applyTransportFailure_retriesOnlyTheStillMatchingDeadAttempt() {
        JoinTaskResult row = row(2);
        when(resultMapper.selectSubmittedForUpdate(26L, "cmd-dead", 2)).thenReturn(row);
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(true, 2, 5));

        service.applyTransportFailure(new JoinTaskDeadCommandCandidate(1L, 26L, "cmd-dead", 2));

        verify(resultMapper).markRetry(26L, "KAFKA_PUBLISH_FAILED", 15_000L, 10_000L);
        verify(resultMapper, never()).markTerminalFailure(anyLong(),
                org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    private void stubSubmitted(JoinTask task, JoinTaskResult row) {
        when(resultMapper.selectSubmittedForUpdate(26L, "cmd-1", row.getAttemptNo())).thenReturn(row);
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task);
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
        return new JoinTaskResultReportedEvent(
                "event-1", 1L, 9L, 26L, 382L, "acc-1", "cmd-1", attemptNo,
                outcome, groupJid, reason, null, retryable, 9_000L, "worker-1");
    }
}
