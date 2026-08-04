package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import org.junit.jupiter.api.Test;

class PullTaskPullExecutionProcessorTest {

    private final PullTaskPullCallPlanningTransactionService planning =
            mock(PullTaskPullCallPlanningTransactionService.class);
    private final PullTaskPullerStationContactProcessor contacts =
            mock(PullTaskPullerStationContactProcessor.class);
    private final PullTaskBatchAddProcessor batch = mock(PullTaskBatchAddProcessor.class);
    private final PullTaskClosingTransactionService closing =
            mock(PullTaskClosingTransactionService.class);
    private final PullTaskPullExecutionProcessor processor =
            new PullTaskPullExecutionProcessor(planning, contacts, batch, closing);

    @Test
    void plannedCallRunsContactsBeforeSubmittingTheFrozenBatch() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        when(planning.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullCallPreparation.ready(call));
        when(contacts.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStationContactStepResult.CALL_READY);
        when(batch.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(batch).process(candidate, call, "worker-1", 1_000L);
    }

    @Test
    void oneContactDirectionIsTheBoundedWorkForThisDispatchRound() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        when(planning.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullCallPreparation.ready(call));
        when(contacts.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStationContactStepResult.MORE_CONTACTS);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verifyNoInteractions(batch);
    }

    @Test
    void submittedCallIsDeferredByBatchTransactionWithoutRunningContacts() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        call.setCallStatus(2);
        when(planning.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskPullCallPreparation.ready(call));
        when(batch.process(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(batch).process(candidate, call, "worker-1", 1_000L);
        verifyNoInteractions(contacts);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(5);
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
}
