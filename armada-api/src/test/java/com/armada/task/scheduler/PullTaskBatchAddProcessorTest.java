package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.junit.jupiter.api.Test;

class PullTaskBatchAddProcessorTest {

    private final PullTaskBatchAddTransactionService transactions =
            mock(PullTaskBatchAddTransactionService.class);
    private final PullTaskBatchAddProcessor processor =
            new PullTaskBatchAddProcessor(transactions);

    @Test
    void delegatesOutboxSubmissionToTransaction() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskPullCall call = call();
        when(transactions.prepare(candidate, call, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, call, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
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

}
