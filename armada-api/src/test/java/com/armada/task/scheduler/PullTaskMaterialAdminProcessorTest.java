package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import org.junit.jupiter.api.Test;

class PullTaskMaterialAdminProcessorTest {

    private final PullTaskMaterialAdminTransactionService transactions =
            mock(PullTaskMaterialAdminTransactionService.class);
    private final PullTaskMaterialAdminProcessor processor =
            new PullTaskMaterialAdminProcessor(transactions);

    @Test
    void delegatesMaterialAdminOutboxSubmissionToTransaction() {
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).prepare(candidate, "worker-1", 1_000L);
    }
}
