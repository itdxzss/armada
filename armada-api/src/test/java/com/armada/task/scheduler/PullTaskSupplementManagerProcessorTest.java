package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskSupplementManagerPayload;
import com.armada.task.model.dto.PullTaskSupplementManagerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OP-01 补充管理员两种进群方式和提权实时复核测试。 */
class PullTaskSupplementManagerProcessorTest {

    private PullTaskSupplementManagerTransactionService transactions;
    private GroupJoinPort joinPort;
    private GroupParticipantPort participantPort;
    private GroupMemberListPort memberListPort;
    private PullTaskSupplementManagerProcessor processor;

    @BeforeEach
    void setUp() {
        transactions = mock(PullTaskSupplementManagerTransactionService.class);
        joinPort = mock(GroupJoinPort.class);
        participantPort = mock(GroupParticipantPort.class);
        memberListPort = mock(GroupMemberListPort.class);
        processor = new PullTaskSupplementManagerProcessor(
                transactions, joinPort, participantPort, memberListPort);
    }

    @Test
    void linkEntryJoinsAndConfirmsTargetMembership() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementManagerWork work = work(
                PullTaskSupplementManagerOperation.JOIN_BY_LINK, target(), target(), false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        when(memberListPort.list(work.targetMemberQuery())).thenReturn(List.of(
                member(target().wsPhone(), false)));
        PullTaskSupplementManagerOutcome outcome =
                PullTaskSupplementManagerOutcome.entryConfirmed();
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(joinPort).join(work.joinCommand());
        verify(transactions).complete(work, outcome, 1_000L);
    }

    @Test
    void managerInviteUsesFrozenExecutorAndNeverCallsTheLinkPort() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementManagerWork work = work(
                PullTaskSupplementManagerOperation.MANAGER_INVITE,
                actor(), target(), false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.ready(work));
        when(participantPort.updateParticipants(
                actor(), "120363group@g.us", List.of(work.targetJid()),
                GroupParticipantAction.ADD)).thenReturn(batch(work.targetJid(), "200"));
        when(memberListPort.list(work.targetMemberQuery())).thenReturn(List.of(
                member(target().wsPhone(), false)));
        PullTaskSupplementManagerOutcome outcome =
                PullTaskSupplementManagerOutcome.entryConfirmed();
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(joinPort, never()).join(work.joinCommand());
        verify(participantPort).updateParticipants(
                actor(), "120363group@g.us", List.of(work.targetJid()),
                GroupParticipantAction.ADD);
    }

    @Test
    void pendingLinkApprovalPausesWithoutQueryingMembership() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementManagerWork work = work(
                PullTaskSupplementManagerOperation.JOIN_BY_LINK, target(), target(), false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.ready(work));
        when(joinPort.join(work.joinCommand())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.PENDING_APPROVAL));
        PullTaskSupplementManagerOutcome outcome =
                PullTaskSupplementManagerOutcome.entryPendingApproval();
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).complete(work, outcome, 1_000L);
        verify(memberListPort, never()).list(work.targetMemberQuery());
    }

    @Test
    void promotionVerifiesActorThenTargetAndAdvancesOnlyAfterAdminFact() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementManagerWork work = work(
                PullTaskSupplementManagerOperation.PROMOTE_ADMIN,
                actor(), target(), false);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.ready(work));
        when(memberListPort.list(work.targetMemberQuery()))
                .thenReturn(List.of(member(target().wsPhone(), false)))
                .thenReturn(List.of(member(target().wsPhone(), true)));
        when(memberListPort.list(work.actorPermissionQuery())).thenReturn(List.of(
                member(actor().wsPhone(), true)));
        when(participantPort.updateParticipants(
                actor(), "120363group@g.us", List.of(work.targetJid()),
                GroupParticipantAction.PROMOTE)).thenReturn(batch(work.targetJid(), "200"));
        PullTaskSupplementManagerOutcome outcome =
                PullTaskSupplementManagerOutcome.adminConfirmed();
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.ADVANCED);

        verify(participantPort).updateParticipants(
                actor(), "120363group@g.us", List.of(work.targetJid()),
                GroupParticipantAction.PROMOTE);
        verify(transactions).complete(work, outcome, 1_000L);
    }

    @Test
    void unknownPromotionOnlyRechecksPermissionAndNeverReplaysPromote() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskSupplementManagerWork work = work(
                PullTaskSupplementManagerOperation.PROMOTE_ADMIN,
                target(), target(), true);
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.ready(work));
        when(memberListPort.list(work.targetMemberQuery())).thenReturn(List.of(
                member(target().wsPhone(), false)));
        PullTaskSupplementManagerOutcome outcome =
                PullTaskSupplementManagerOutcome.adminUnknown(
                        "MANAGER_ADMIN_PERMISSION_UNCONFIRMED");
        when(transactions.complete(work, outcome, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.processIfPresent(candidate, "worker", 1_000L))
                .contains(PullTaskExecutionDispatchResult.DEFERRED);

        verify(participantPort, never()).updateParticipants(
                target(), "120363group@g.us", List.of(work.targetJid()),
                GroupParticipantAction.PROMOTE);
    }

    @Test
    void returnsEmptyWhenTheClaimedRowHasNoSupplementInstruction() {
        PullTaskGroupExecution candidate = candidate();
        when(transactions.prepare(candidate, "worker", 1_000L))
                .thenReturn(PullTaskSupplementManagerPreparation.notHandled());

        Optional<PullTaskExecutionDispatchResult> result =
                processor.processIfPresent(candidate, "worker", 1_000L);

        assertThat(result).isEmpty();
    }

    private static PullTaskSupplementManagerWork work(
            PullTaskSupplementManagerOperation operation,
            ProtocolAccountRef actor,
            ProtocolAccountRef target,
            boolean verificationOnly) {
        PullTaskSupplementManagerPayload payload = new PullTaskSupplementManagerPayload(
                operation,
                new PullTaskSupplementManagerPayload.Accounts(actor, target),
                new PullTaskSupplementManagerPayload.Group(
                        "chat.whatsapp.com/AAAA", "120363group@g.us", "op-201"),
                new PullTaskExecutionLease("worker", 2),
                verificationOnly);
        return new PullTaskSupplementManagerWork(7L, 11L, 201L, 301L, payload);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(1L);
        row.setTenantId(7L);
        row.setExecutionStatus(2);
        row.setStage(2);
        row.setVersion(2);
        row.setLockOwner("worker");
        return row;
    }

    private static ProtocolAccountRef actor() {
        return new ProtocolAccountRef(
                901L, ProtocolBackend.WEB, "acc-901", "8613800000901");
    }

    private static ProtocolAccountRef target() {
        return new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "acc-902", "8613800000902");
    }

    private static GroupParticipantResult member(String phone, boolean admin) {
        return new GroupParticipantResult(
                phone + "@s.whatsapp.net", phone, admin, false, null);
    }

    private static GroupParticipantBatchResult batch(String jid, String status) {
        return new GroupParticipantBatchResult(false, List.of(
                new GroupParticipantBatchResult.Item(jid, status, status)));
    }
}
