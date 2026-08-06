package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.junit.jupiter.api.Test;

class PullTaskManagerPullerContactProcessorTest {

    private final PullTaskManagerPullerContactTransactionService transactions =
            mock(PullTaskManagerPullerContactTransactionService.class);
    private final PullTaskSupplementPullerProcessor supplementProcessor =
            mock(PullTaskSupplementPullerProcessor.class);
    private final PullTaskManagerPullerContactProcessor processor =
            new PullTaskManagerPullerContactProcessor(transactions, supplementProcessor);

    @Test
    void returnsTransactionalOutboxSubmissionResultWithoutCallingProtocolSynchronously() {
        PullTaskGroupExecution candidate = candidate();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setVersion(4);
        row.setLockOwner("worker-1");
        return row;
    }
}
