package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.junit.jupiter.api.Test;

class PullTaskPullerInviteProcessorTest {

    private final PullTaskPullerInviteTransactionService transactions =
            mock(PullTaskPullerInviteTransactionService.class);
    private final PullTaskPullerInviteProcessor processor =
            new PullTaskPullerInviteProcessor(transactions);

    @Test
    void returnsTransactionalOutboxSubmissionWithoutCallingProtocolSynchronously() {
        PullTaskGroupExecution candidate = candidate();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).prepare(candidate, "worker-1", 1_000L);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        row.setVersion(5);
        row.setLockOwner("worker-1");
        return row;
    }
}
