package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskSupplementPullerPayload;
import com.armada.task.model.dto.PullTaskSupplementPullerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OP-02 补充拉手踩链接命令与只查不重放语义测试。 */
class PullTaskSupplementPullerProcessorTest {

    private PullTaskSupplementPullerTransactionService transactions;
    private GroupJoinPort joinPort;
    private GroupMemberListPort memberListPort;
    private PullTaskSupplementPullerProcessor processor;

    @BeforeEach
    void setUp() {
        transactions = mock(PullTaskSupplementPullerTransactionService.class);
        joinPort = mock(GroupJoinPort.class);
        memberListPort = mock(GroupMemberListPort.class);
        processor = new PullTaskSupplementPullerProcessor(
                transactions, joinPort, memberListPort);
    }

    @Test
    void linkEntryConfirmsMembershipBeforeTheContactChainContinues() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementPullerWork work = work(false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementPullerPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.memberQuery())).thenReturn(List.of(member()));
        PullTaskSupplementPullerOutcome outcome =
                PullTaskSupplementPullerOutcome.confirmed();
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(joinPort).join(work.joinCommand());
        verify(transactions).complete(work, outcome, 1_000L);
    }

    @Test
    void unknownWorkOnlyRechecksMembershipAndNeverReplaysTheLinkCommand() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementPullerWork work = work(true);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementPullerPreparation.ready(work));
        when(memberListPort.list(work.memberQuery())).thenReturn(List.of());
        PullTaskSupplementPullerOutcome outcome =
                PullTaskSupplementPullerOutcome.unknown(
                        "PULLER_MEMBERSHIP_UNCONFIRMED");
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(joinPort, never()).join(work.joinCommand());
    }

    @Test
    void pendingApprovalRemainsUnknownInsteadOfBecomingAStableFailure() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementPullerWork work = work(false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementPullerPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.PENDING_APPROVAL));
        when(memberListPort.list(work.memberQuery())).thenReturn(List.of());
        PullTaskSupplementPullerOutcome outcome =
                PullTaskSupplementPullerOutcome.unknown("PULLER_JOIN_PENDING_APPROVAL");
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).complete(work, outcome, 1_000L);
    }

    @Test
    void joinedResponseWithoutRealtimeMembershipKeepsAnExplicitUnknownReason() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementPullerWork work = work(false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementPullerPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.memberQuery())).thenReturn(List.of());
        PullTaskSupplementPullerOutcome outcome =
                PullTaskSupplementPullerOutcome.unknown(
                        "PULLER_MEMBERSHIP_UNCONFIRMED");
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).complete(work, outcome, 1_000L);
    }

    @Test
    void returnsEmptyForManagerInviteOrInitialPullers() {
        PullTaskGroupExecution candidate = candidate();
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementPullerPreparation.notHandled());

        Optional<PullTaskExecutionDispatchResult> result =
                processor.processIfPresent(candidate, "worker", 1_000L);

        assertThat(result).isEmpty();
    }

    private static PullTaskSupplementPullerWork work(boolean verificationOnly) {
        PullTaskSupplementPullerPayload payload = new PullTaskSupplementPullerPayload(
                target(),
                new PullTaskSupplementPullerPayload.Group(
                        "chat.whatsapp.com/AAAA", "120363group@g.us", "op-301"),
                new PullTaskExecutionLease("worker", 2), verificationOnly);
        return new PullTaskSupplementPullerWork(7L, 11L, 301L, 401L, payload);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(1L);
        row.setTenantId(7L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setVersion(2);
        row.setLockOwner("worker");
        return row;
    }

    private static ProtocolAccountRef target() {
        return new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "acc-902", "8613800000902");
    }

    private static GroupParticipantResult member() {
        return new GroupParticipantResult(
                "8613800000902@s.whatsapp.net", "8613800000902", false, false, null);
    }
}
