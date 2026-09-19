package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.task.model.dto.JoinTaskAdminWork;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.armada.task.model.dto.JoinTaskAdminObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用新鲜成员身份与角色确认提权权限，覆盖 LID 和不完整快照。 */
class JoinTaskAdminFactsTest {
    private final AccountProtocolLookupService accounts = mock(AccountProtocolLookupService.class);
    private final GroupExecutionAccountSelector selector = mock(GroupExecutionAccountSelector.class);
    private final FixedAccountGroupMetadataPort metadata = mock(FixedAccountGroupMetadataPort.class);
    private final JoinTaskAdminFacts facts = new JoinTaskAdminFacts(accounts, selector, metadata,
            mock(GroupParticipantObservationService.class));
    private final GroupExecutionAccount actor = new GroupExecutionAccount(40L, "ANDROID", "actor", "67890", true);
    private JoinTaskAdminWork work;

    @BeforeEach
    void setup() {
        var task = new JoinTask(); task.setOwnerUserId(90L);
        var row = new JoinTaskResult(); row.setTenantId(7L); row.setAccountId(30L); row.setGroupJid("123@g.us");
        work = new JoinTaskAdminWork(task, row);
        when(accounts.findActiveProtocolRef(30L)).thenReturn(Optional.of(
                new ProtocolAccountRef(30L, ProtocolBackend.WEB, "target", "12345")));
        when(selector.findJoinTaskAdminCandidates(7L, "123@g.us", 30L, 90L)).thenReturn(List.of(actor));
    }

    @Test
    void existingAdminPromotesPnResolvedTargetUsingActorsBackend() {
        when(metadata.getMetadata(actor.protocolRef(), "123@g.us")).thenReturn(snapshot(true,
                member("888@lid", "12345@s.whatsapp.net", false), member("67890@s.whatsapp.net", null, true)));
        var result = facts.inspect(work);
        assertEquals(READY, result.kind());
        assertEquals(40L, result.actor().armadaAccountId());
        assertEquals(ProtocolBackend.ANDROID, result.actor().backend());
    }

    @Test
    void staleLocalAdminDoesNotAuthorizePromotion() {
        when(metadata.getMetadata(actor.protocolRef(), "123@g.us")).thenReturn(snapshot(true,
                member("12345@s.whatsapp.net", null, false), member("67890@s.whatsapp.net", null, false)));
        assertEquals(WAIT, facts.inspect(work).kind());
    }

    @Test
    void incompleteLidOnlySnapshotDoesNotMisidentifyTargetOrDeclareItAbsent() {
        when(metadata.getMetadata(actor.protocolRef(), "123@g.us")).thenReturn(snapshot(false,
                member("12345@lid", null, true)));
        assertEquals(WAIT, facts.inspect(work).kind());
        when(metadata.getMetadata(actor.protocolRef(), "123@g.us")).thenReturn(snapshot(true));
        assertEquals(FAILED, facts.inspect(work).kind());
    }

    @Test
    void alreadyPromotedTargetCompletesWithoutSendingAnotherCommand() {
        when(metadata.getMetadata(actor.protocolRef(), "123@g.us")).thenReturn(snapshot(true,
                member("888@lid", "12345@s.whatsapp.net", true)));
        assertEquals(SUCCESS, facts.inspect(work).kind());
        assertNull(facts.inspect(work).actor());
    }

    private GroupParticipantResult member(String jid, String pn, boolean admin) {
        return new GroupParticipantResult(jid, pn, null, admin, false, admin ? "admin" : null);
    }

    private GroupMetadataResult snapshot(boolean complete, GroupParticipantResult... members) {
        return new GroupMetadataResult("123@g.us", null, null, null, null, null, complete,
                null, null, null, null, null, null, false, null, false, true, List.of(members));
    }
}
