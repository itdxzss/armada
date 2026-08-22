package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import org.junit.jupiter.api.Test;

class PullTaskPullExecutionProcessorTest {

    private final PullTaskPullWavePlanningTransactionService waves =
            mock(PullTaskPullWavePlanningTransactionService.class);
    private final PullTaskStickyPullerTransactionService pullers =
            mock(PullTaskStickyPullerTransactionService.class);
    private final PullTaskPullWaveSettlementTransactionService settlement =
            mock(PullTaskPullWaveSettlementTransactionService.class);
    private final PullTaskPullerStationContactProcessor contacts =
            mock(PullTaskPullerStationContactProcessor.class);
    private final PullTaskBatchAddProcessor batch = mock(PullTaskBatchAddProcessor.class);
    private final PullTaskClosingTransactionService closing =
            mock(PullTaskClosingTransactionService.class);
    private final PullTaskCreatorLeaveProcessor creatorLeave =
            mock(PullTaskCreatorLeaveProcessor.class);
    private final PullTaskPullExecutionProcessor processor = new PullTaskPullExecutionProcessor(
            new PullTaskPullExecutionDispatchResources(
                    waves, pullers, settlement, contacts, batch), creatorLeave, closing);

    @Test
    void dispatchingWaveBindsStickyPullerBeforeContactsAndBatch() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        PullTaskPullWave wave = wave(PullTaskPullWaveStatus.DISPATCHING);
        PullTaskStickyPullerSelection selected = selectedPuller();
        when(waves.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullWavePreparation.ready(wave, call));
        when(pullers.bindForDispatch(candidate, call, "worker-1", 1_000L))
                .thenReturn(selected);
        when(contacts.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStationContactStepResult.CALL_READY);
        when(batch.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(pullers).bindForDispatch(candidate, call, "worker-1", 1_000L);
        verify(batch).process(candidate, call, "worker-1", 1_000L);
    }

    @Test
    void collectingWaveRoutesDirectlyToSettlement() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullWave wave = wave(PullTaskPullWaveStatus.COLLECTING);
        when(waves.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullWavePreparation.ready(wave, null));
        when(settlement.settle(candidate, wave, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(settlement).settle(candidate, wave, "worker-1", 1_000L);
        verifyNoInteractions(pullers, contacts, batch);
    }

    @Test
    void unavailableStickyPullerStopsBeforeContacts() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        PullTaskPullWave wave = wave(PullTaskPullWaveStatus.DISPATCHING);
        when(waves.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullWavePreparation.ready(wave, call));
        when(pullers.bindForDispatch(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStickyPullerSelection.completed(
                        PullTaskExecutionDispatchResult.DEFERRED));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verifyNoInteractions(contacts, batch);
    }

    @Test
    void oneContactDirectionRemainsTheBoundedWorkForOneClaim() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        PullTaskPullWave wave = wave(PullTaskPullWaveStatus.DISPATCHING);
        when(waves.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullWavePreparation.ready(wave, call));
        when(pullers.bindForDispatch(candidate, call, "worker-1", 1_000L))
                .thenReturn(selectedPuller());
        when(contacts.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStationContactStepResult.MORE_CONTACTS);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verifyNoInteractions(batch);
    }

    @Test
    void closingAttemptsIndependentCreatorLeaveBeforeExistingCloseFlow() {
        PullTaskGroupExecution candidate = candidate();
		when(creatorLeave.process(candidate, "worker-1", 1_000L))
				.thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
        when(closing.close(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.close(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(creatorLeave, closing);
        order.verify(creatorLeave).process(candidate, "worker-1", 1_000L);
        order.verify(closing).close(candidate, "worker-1", 1_000L);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        row.setVersion(6);
        row.setLockOwner("worker-1");
        return row;
    }

    private static PullTaskPullCall call() {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setId(801L);
        row.setGroupExecutionId(11L);
        row.setCallStatus(1);
        return row;
    }

    private static PullTaskPullWave wave(PullTaskPullWaveStatus status) {
        PullTaskPullWave row = new PullTaskPullWave();
        row.setId(701L);
        row.setGroupExecutionId(11L);
        row.setWaveStatus(status.code());
        return row;
    }

    private static PullTaskStickyPullerSelection selectedPuller() {
        PullTaskGroupAccount role = new PullTaskGroupAccount();
        role.setId(901L);
        role.setAccountId(902L);
        return PullTaskStickyPullerSelection.ready(
                role,
                new ProtocolAccountRef(
                        902L, ProtocolBackend.WEB, "puller-902", "8613800000902"),
                1L);
    }
}
