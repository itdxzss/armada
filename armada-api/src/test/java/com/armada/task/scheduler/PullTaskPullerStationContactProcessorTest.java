package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import org.junit.jupiter.api.Test;

class PullTaskPullerStationContactProcessorTest {

    private final PullTaskPullerStationContactTransactionService transactions =
            mock(PullTaskPullerStationContactTransactionService.class);
    private final PullTaskPullerStationContactProcessor processor =
            new PullTaskPullerStationContactProcessor(transactions);

    @Test
    void returnsTransactionalOutboxSubmissionWithoutCallingProtocolSynchronously() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        when(transactions.prepare(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskStationContactStepResult.MORE_CONTACTS);

        assertThat(processor.process(candidate, call, "worker-1", 1_000L))
                .isEqualTo(PullTaskStationContactStepResult.MORE_CONTACTS);

        verify(transactions).prepare(candidate, call, "worker-1", 1_000L);
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
        row.setPullerGroupAccountId(501L);
        row.setCallStatus(1);
        row.setPlannedStationCount(1);
        return row;
    }
}
