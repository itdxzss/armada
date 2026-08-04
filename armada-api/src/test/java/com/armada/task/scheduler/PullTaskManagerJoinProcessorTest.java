package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskManagerJoinProcessorTest {

    private final PullTaskManagerJoinTransactionService transactions =
            mock(PullTaskManagerJoinTransactionService.class);
    private final GroupJoinPort joinPort = mock(GroupJoinPort.class);
    private final GroupMemberListPort memberListPort = mock(GroupMemberListPort.class);
    private final PullTaskSupplementManagerProcessor supplementProcessor =
            mock(PullTaskSupplementManagerProcessor.class);
    private final PullTaskManagerJoinProcessor processor =
            new PullTaskManagerJoinProcessor(
                    transactions, supplementProcessor, joinPort, memberListPort);

    @Test
    void advancesOnlyAfterTheSelectedManagerAppearsInTheLiveMemberList() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(joinPort.join(work.joinCommand()))
                .thenReturn(new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.memberListQuery("120363group@g.us")))
                .thenReturn(List.of(new GroupParticipantResult(
                        "8613800000901@s.whatsapp.net", "8613800000901",
                        false, false, null)));
        when(transactions.complete(work,
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"), 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
    }

    @Test
    void missingSelfMembershipIsPersistedAsUnknownInsteadOfSuccess() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(joinPort.join(work.joinCommand()))
                .thenReturn(new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.memberListQuery("120363group@g.us")))
                .thenReturn(List.of(new GroupParticipantResult(
                        "8613800000002@s.whatsapp.net", "8613800000002",
                        false, false, null)));
        PullTaskManagerJoinOutcome unknown = PullTaskManagerJoinOutcome.unconfirmed(
                "120363group@g.us", "MANAGER_MEMBERSHIP_UNCONFIRMED");
        when(transactions.complete(work, unknown, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions).complete(work, unknown, 1_000L);
    }

    @Test
    void memberListFailureAfterJoinIsUnknownEvenWhenTheQueryErrorIsNonRetryable() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(joinPort.join(work.joinCommand()))
                .thenReturn(new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.memberListQuery("120363group@g.us")))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_PERMISSION_DENIED, "query denied"));
        PullTaskManagerJoinOutcome unknown = PullTaskManagerJoinOutcome.unconfirmed(
                "120363group@g.us", "MANAGER_MEMBERSHIP_UNCONFIRMED");
        when(transactions.complete(work, unknown, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions).complete(work, unknown, 1_000L);
    }

    @Test
    void revokedInviteFailsTheExecutionInsteadOfCyclingThroughManagers() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskManagerJoinWork work = work();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerJoinPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenThrow(
                new ProtocolException(ProtocolErrorCode.INVITE_REVOKED, "revoked"));
        PullTaskManagerJoinOutcome failed =
                PullTaskManagerJoinOutcome.executionFailed("INVITE_REVOKED");
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
        when(memberListPort.list(work.memberListQuery("120363group@g.us")))
                .thenReturn(List.of(new GroupParticipantResult(
                        "8613800000901@s.whatsapp.net", "8613800000901",
                        false, false, null)));
        PullTaskManagerJoinOutcome confirmed =
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us");
        when(transactions.complete(work, confirmed, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(joinPort, never()).join(work.joinCommand());
        verify(transactions).complete(work, confirmed, 1_000L);
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
