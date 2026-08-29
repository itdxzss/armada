package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** 三模式 round 推进、零账号等待和 plannedEnd 收口。 */
class HyperlinkRoundLifecycleServiceTest {
    private static final long NOW = 2_000_000L;

    private final HyperlinkTaskMapper tasks = mock(HyperlinkTaskMapper.class);
    private final HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
    private final HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
    private final HyperlinkTaskRoundAccountMapper roundAccounts =
            mock(HyperlinkTaskRoundAccountMapper.class);
    private final HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
    private final HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
    private final HyperlinkRoundAccountSelectionService selection =
            mock(HyperlinkRoundAccountSelectionService.class);
    private final HyperlinkCleanupStartService cleanup = mock(HyperlinkCleanupStartService.class);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private final HyperlinkRoundLifecycleService service = new HyperlinkRoundLifecycleService(
            tasks, runtimes, rounds, roundAccounts, recipients, usages, selection, cleanup, clock);

    @BeforeEach
    void commonRunningFacts() {
        TenantContext.set(7L);
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(runtime());
        when(recipients.countPendingUnassigned(11L)).thenReturn(8);
        when(recipients.countSendingByRoundId(21L)).thenReturn(0);
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(0);
        when(roundAccounts.countByRoundId(21L)).thenReturn(0);
        when(rounds.beginSelection(21L, HyperlinkTaskRoundStatus.NO_ACCOUNT.code(), NOW))
                .thenReturn(1);
        when(selection.select(any(), any(), anyLong())).thenReturn(0);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void instantZeroAccountWaitsAndRechecksTheSameRound() {
        when(tasks.selectById(11L)).thenReturn(task(HyperlinkTaskMode.INSTANT));
        when(rounds.selectActive(11L)).thenReturn(round(HyperlinkTaskRoundStatus.NO_ACCOUNT));

        service.advance(11L);

        verify(rounds).updateSelection(21L, 0, 0,
                HyperlinkTaskRoundStatus.NO_ACCOUNT.code(), NOW + 30_000L, NOW);
        verify(rounds, never()).insert(any());
    }

    @Test
    void rollingZeroAccountLeavesTheSameRecipientAndRoundForLaterSelection() {
        HyperlinkTask task = task(HyperlinkTaskMode.ROLLING);
        task.setTaskPlannedEndAt(NOW + 600_000L);
        when(tasks.selectById(11L)).thenReturn(task);
        when(rounds.selectActive(11L)).thenReturn(round(HyperlinkTaskRoundStatus.NO_ACCOUNT));

        service.advance(11L);

        verify(rounds).updateSelection(21L, 0, 0,
                HyperlinkTaskRoundStatus.NO_ACCOUNT.code(), NOW + 30_000L, NOW);
        verify(recipients, never()).failUnassignedBatch(
                anyLong(), any(), any(), anyLong(), anyInt());
        verify(rounds, never()).insert(any());
    }

    @Test
    void cycleClosesExhaustedRoundAndPlansOnlyTheNextRound() {
        HyperlinkTask task = task(HyperlinkTaskMode.CYCLE);
        task.setTaskIntervalMinutes(10);
        when(tasks.selectById(11L)).thenReturn(task);
        when(rounds.selectActive(11L)).thenReturn(round(HyperlinkTaskRoundStatus.NO_ACCOUNT));
        when(rounds.markCompleted(21L, NOW)).thenReturn(1);
        doAnswer(invocation -> {
            HyperlinkTaskRound inserted = invocation.getArgument(0);
            inserted.setId(22L);
            return 1;
        }).when(rounds).insert(any());

        service.advance(11L);

        ArgumentCaptor<HyperlinkTaskRound> next = ArgumentCaptor.forClass(HyperlinkTaskRound.class);
        verify(rounds).insert(next.capture());
        assertThat(next.getValue().getRoundNo()).isEqualTo(4L);
        assertThat(next.getValue().getRoundStatus()).isEqualTo(HyperlinkTaskRoundStatus.PLANNED.code());
        assertThat(next.getValue().getScheduledAt()).isEqualTo(NOW);
        verify(runtimes).updateCurrentRound(11L, 22L, 4L, 0, NOW);
    }

    @Test
    void rollingPlannedEndStopsBeforeAnyFurtherSelection() {
        HyperlinkTask task = task(HyperlinkTaskMode.ROLLING);
        task.setTaskPlannedEndAt(NOW);
        when(tasks.selectById(11L)).thenReturn(task);
        when(rounds.selectActive(11L)).thenReturn(round(HyperlinkTaskRoundStatus.NO_ACCOUNT));
        when(runtimes.stopAtDeadline(11L, NOW)).thenReturn(1);

        service.advance(11L);

        verify(cleanup).begin(11L, true, NOW);
        verify(selection, never()).select(any(), any(), anyLong());
    }

    @Test
    void futurePlannedRoundCannotSelectAccountsBeforeScheduledTime() {
        when(tasks.selectById(11L)).thenReturn(task(HyperlinkTaskMode.CYCLE));
        HyperlinkTaskRound future = round(HyperlinkTaskRoundStatus.PLANNED);
        future.setScheduledAt(NOW + 1);
        when(rounds.selectActive(11L)).thenReturn(future);

        service.advance(11L);

        verify(rounds, never()).beginSelection(anyLong(), anyInt(), anyLong());
        verify(selection, never()).select(any(), any(), anyLong());
    }

    @Test
    void duePlannedRoundUsesScheduledOrCurrentTimeAsItsDispatchGate() {
        when(tasks.selectById(11L)).thenReturn(task(HyperlinkTaskMode.CYCLE));
        HyperlinkTaskRound planned = round(HyperlinkTaskRoundStatus.PLANNED);
        HyperlinkTaskRound ready = round(HyperlinkTaskRoundStatus.READY);
        ready.setActualConcurrency(1);
        when(rounds.selectActive(11L)).thenReturn(planned, ready, ready);
        when(rounds.beginSelection(21L, HyperlinkTaskRoundStatus.PLANNED.code(), NOW))
                .thenReturn(1);
        when(selection.select(any(), any(), anyLong())).thenReturn(1);
        when(roundAccounts.countByRoundId(21L)).thenReturn(1);
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(1);

        service.advance(11L);

        verify(rounds).updateSelection(21L, 1, 1,
                HyperlinkTaskRoundStatus.READY.code(), NOW, NOW);
    }

    @Test
    void lifecycleAlwaysLocksRuntimeBeforeReadingOrWritingRound() {
        when(tasks.selectById(11L)).thenReturn(task(HyperlinkTaskMode.INSTANT));
        when(rounds.selectActive(11L)).thenReturn(round(HyperlinkTaskRoundStatus.NO_ACCOUNT));

        service.advance(11L);

        InOrder order = inOrder(runtimes, rounds, roundAccounts, usages, recipients);
        order.verify(runtimes).selectByTaskIdForUpdate(7L, 11L);
        order.verify(rounds).selectActive(11L);
        order.verify(roundAccounts).syncUnavailableFromUsage(21L, NOW);
        order.verify(recipients).countPendingUnassigned(11L);
        order.verify(recipients).countSendingByRoundId(21L);
        order.verify(roundAccounts).countAvailableByRoundId(21L);
        order.verify(rounds).beginSelection(21L, HyperlinkTaskRoundStatus.NO_ACCOUNT.code(), NOW);
        order.verify(roundAccounts).countByRoundId(21L);
        order.verify(rounds).updateSelection(21L, 0, 0,
                HyperlinkTaskRoundStatus.NO_ACCOUNT.code(), NOW + 30_000L, NOW);
        order.verify(runtimes).updateCurrentRound(11L, 21L, 3L, 0, NOW);
    }

    @Test
    void startDueLocksRuntimeBeforeStartingRound() {
        HyperlinkTaskRuntime notStarted = runtime();
        notStarted.setRunStatus(HyperlinkTaskRunStatus.NOT_STARTED.code());
        HyperlinkTaskRound ready = round(HyperlinkTaskRoundStatus.READY);
        ready.setActualConcurrency(2);
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(notStarted);
        when(rounds.selectActive(11L)).thenReturn(ready);
        when(rounds.markStarted(21L, NOW)).thenReturn(1);

        service.startDue(11L);

        InOrder order = inOrder(runtimes, rounds);
        order.verify(runtimes).selectByTaskIdForUpdate(7L, 11L);
        order.verify(rounds).selectActive(11L);
        order.verify(rounds).markStarted(21L, NOW);
        order.verify(runtimes).startRound(11L, 21L, 3L, 2, NOW);
    }

    @Test
    void startDueDoesNotStartRuntimeWhenRoundCasLosesToScheduleEdit() {
        HyperlinkTaskRuntime notStarted = runtime();
        notStarted.setRunStatus(HyperlinkTaskRunStatus.NOT_STARTED.code());
        HyperlinkTaskRound ready = round(HyperlinkTaskRoundStatus.READY);
        ready.setActualConcurrency(2);
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(notStarted);
        when(rounds.selectActive(11L)).thenReturn(ready);
        when(rounds.markStarted(21L, NOW)).thenReturn(0);

        service.startDue(11L);

        verify(runtimes, never()).startRound(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(runtimes, never()).updateCurrentRound(
                anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    private HyperlinkTask task(HyperlinkTaskMode mode) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(11L);
        task.setTaskType(mode.code());
        task.setConcurrentNum(2);
        task.setMaxUseAccount(2);
        return task;
    }

    private HyperlinkTaskRuntime runtime() {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setHyperlinkTaskId(11L);
        runtime.setRunStatus(HyperlinkTaskRunStatus.RUNNING.code());
        return runtime;
    }

    private HyperlinkTaskRound round(HyperlinkTaskRoundStatus status) {
        HyperlinkTaskRound round = new HyperlinkTaskRound();
        round.setId(21L);
        round.setHyperlinkTaskId(11L);
        round.setRoundNo(3L);
        round.setRoundStatus(status.code());
        round.setScheduledAt(1_000_000L);
        round.setActualConcurrency(0);
        return round;
    }
}
