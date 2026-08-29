package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskManagerJoinProtocolExecutorTest {

    private final GroupJoinPort joinPort = mock(GroupJoinPort.class);
    private final GroupInviteLinkService inviteLinkService = mock(GroupInviteLinkService.class);
    private final PullTaskManagerJoinProtocolExecutor executor =
            new PullTaskManagerJoinProtocolExecutor(joinPort, inviteLinkService);

    @Test
    void revokedInviteUsesRefreshedCurrentCodeForOneWebRetry() {
        PullTaskGroupExecution candidate = revokedCandidate();
        PullTaskManagerJoinWork work = work(ProtocolBackend.WEB);
        when(inviteLinkService.refreshCurrentInviteCode(
                51L, "120363group@g.us", "OldInviteCode"))
                .thenReturn(Optional.of("NewInviteCode"));
        when(joinPort.join(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GroupJoinResult(
                        "120363group@g.us", GroupJoinOutcome.JOINED));

        PullTaskManagerJoinOutcome outcome = executor.join(candidate, work);

        assertThat(outcome).isEqualTo(
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"));
        ArgumentCaptor<GroupJoinCommand> command = ArgumentCaptor.forClass(GroupJoinCommand.class);
        verify(joinPort).join(command.capture());
        assertThat(command.getValue().inviteLinkOrCode())
                .isEqualTo("https://chat.whatsapp.com/NewInviteCode");
        assertThat(command.getValue().operationId())
                .isEqualTo("pull-task-manager-join:601");
        verify(inviteLinkService).bindGroupJid(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq("120363group@g.us"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void revokedInviteWithoutReplacementFailsAfterTheSingleRecoveryAttempt() {
        PullTaskGroupExecution candidate = revokedCandidate();
        PullTaskManagerJoinWork work = work(ProtocolBackend.ANDROID);
        when(inviteLinkService.refreshCurrentInviteCode(
                51L, "120363group@g.us", "OldInviteCode"))
                .thenReturn(Optional.empty());

        PullTaskManagerJoinOutcome outcome = executor.join(candidate, work);

        assertThat(outcome).isEqualTo(
                PullTaskManagerJoinOutcome.executionFailed("INVITE_REVOKED"));
        verify(joinPort, never()).join(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void freshJoinWithNullReasonCodeJoinsDirectlyWithoutThrowing() {
        PullTaskGroupExecution candidate = freshCandidate();
        PullTaskManagerJoinWork work = work(ProtocolBackend.WEB);
        when(joinPort.join(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GroupJoinResult(
                        "120363group@g.us", GroupJoinOutcome.JOINED));

        PullTaskManagerJoinOutcome outcome = executor.join(candidate, work);

        assertThat(outcome).isEqualTo(
                PullTaskManagerJoinOutcome.confirmed("120363group@g.us"));
        verify(inviteLinkService, never()).refreshCurrentInviteCode(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(joinPort).join(org.mockito.ArgumentMatchers.any());
    }

    private static PullTaskGroupExecution revokedCandidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setGroupLinkId(51L);
        row.setNormalizedLink("chat.whatsapp.com/OldInviteCode");
        row.setInviteCode("OldInviteCode");
        row.setGroupJid("120363group@g.us");
        row.setReasonCode("INVITE_REVOKED");
        return row;
    }

    private static PullTaskGroupExecution freshCandidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setGroupLinkId(51L);
        row.setNormalizedLink("chat.whatsapp.com/OldInviteCode");
        row.setInviteCode("OldInviteCode");
        return row;
    }

    private static PullTaskManagerJoinWork work(ProtocolBackend backend) {
        ProtocolAccountRef account = new ProtocolAccountRef(
                901L, backend, "acc-901", "8613800000901");
        return new PullTaskManagerJoinWork(7L, 11L, 501L, 601L,
                new PullTaskManagerJoinPayload(
                        account, "chat.whatsapp.com/OldInviteCode",
                        "pull-task-manager-join:601", "worker-1", 2));
    }
}
