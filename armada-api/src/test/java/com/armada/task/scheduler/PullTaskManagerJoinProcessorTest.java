package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskManagerJoinProcessorTest {

    private final PullTaskExecutionTransactionService executionTransactions =
            mock(PullTaskExecutionTransactionService.class);
    private final PullTaskManagerJoinTransactionService transactions =
            mock(PullTaskManagerJoinTransactionService.class);
    private final PullTaskManagerJoinProtocolExecutor protocolExecutor =
            mock(PullTaskManagerJoinProtocolExecutor.class);
    private final PullTaskMemberQueryAwaitService memberQueryAwaitService =
            mock(PullTaskMemberQueryAwaitService.class);
    private final PullTaskSupplementManagerProcessor supplementProcessor =
            mock(PullTaskSupplementManagerProcessor.class);
    private final PullTaskManagerJoinProcessor processor =
            new PullTaskManagerJoinProcessor(
                    executionTransactions, transactions,
                    supplementProcessor, protocolExecutor, memberQueryAwaitService);

    @Test
    void startsNewManagerJoinRowBeforeSubmittingTheProtocolCommand() {
        PullTaskGroupExecution candidate = candidate();
        candidate.setExecutionStatus(PullTaskExecutionStatus.WAIT_START.code());
        candidate.setVersion(1);
        PullTaskExecutionWork started = new PullTaskExecutionWork(
                7L, 11L, "chat.whatsapp.com/AAAA", "AAAA",
                new PullTaskExecutionLease("worker-1", 2));
        when(executionTransactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(java.util.Optional.of(started));
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.completed(
                        PullTaskExecutionDispatchResult.DEFERRED));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(candidate.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(candidate.getVersion()).isEqualTo(2);
        verify(executionTransactions).prepare(candidate, "worker-1", 1_000L);
    }

    @Test
    void freshJoinedResultAdvancesWithoutQueryingTheLiveMemberList() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(protocolExecutor.join(candidate, work)).thenReturn(
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"));
        when(transactions.complete(work,
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"), 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verifyNoInteractions(memberQueryAwaitService);
    }

    @Test
    void pendingApprovalPausesTheGroupWithoutQueryingTheLiveMemberList() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        PullTaskManagerJoinOutcome pending = PullTaskManagerJoinOutcome.pendingApproval(
                "120363group@g.us");
        when(protocolExecutor.join(candidate, work)).thenReturn(pending);
        when(transactions.complete(work, pending, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verifyNoInteractions(memberQueryAwaitService);
        verify(transactions).complete(work, pending, 1_000L);
    }

    @Test
    void revokedInviteFailsTheExecutionInsteadOfCyclingThroughManagers() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        PullTaskManagerJoinOutcome failed =
                PullTaskManagerJoinOutcome.executionFailed("INVITE_REVOKED");
        when(protocolExecutor.join(candidate, work)).thenReturn(failed);
        when(transactions.complete(work, failed, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.FAILED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.FAILED);
        verify(transactions).complete(work, failed, 1_000L);
    }

    @Test
    void restartRecoveryVerifiesKnownGroupWithoutReplayingJoin() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = recoveryWork();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(memberQueryAwaitService.readOrDefer(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(1_000L)))
                .thenReturn(PullTaskMemberQueryResult.available(701L, List.of(
                        new PullTaskMemberFact(
                                "8613800000901@s.whatsapp.net",
                                "8613800000901@s.whatsapp.net", "8613800000901",
                                true, false))));
        PullTaskManagerJoinOutcome confirmed =
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us");
        when(transactions.complete(work, confirmed, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(protocolExecutor, never()).join(candidate, work);
        verify(transactions).complete(work, confirmed, 1_000L);
    }

    @Test
    void restartRecoveryPendingQueryReleasesLeaseWithoutCompletingStage() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = recoveryWork();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(memberQueryAwaitService.readOrDefer(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(PullTaskMemberQueryResult.pending(701L, 31_000L));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions, never()).complete(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(2);
        row.setVersion(2);
        row.setLockOwner("worker-1");
        return row;
    }

    private static PullTaskManagerJoinWork work() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                901L, ProtocolBackend.WEB, "acc-901", "8613800000901");
        return new PullTaskManagerJoinWork(7L, 11L, 501L, 601L,
                new PullTaskManagerJoinPayload(account, "chat.whatsapp.com/AAAA",
                        "pull-task-manager-join:601", "worker-1", 2));
    }

    private static PullTaskManagerJoinWork recoveryWork() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                901L, ProtocolBackend.WEB, "acc-901", "8613800000901");
        return new PullTaskManagerJoinWork(7L, 11L, 501L, 601L,
                new PullTaskManagerJoinPayload(account, "chat.whatsapp.com/AAAA",
                        "pull-task-manager-join:601", "worker-1", 2,
                        "120363group@g.us"));
    }
}
