package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.task.model.dto.JoinTaskCleanupContext;
import com.armada.task.model.dto.JoinTaskCleanupWork;
import com.armada.task.model.entity.JoinTaskCleanup;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskCleanupStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 外部操作只执行当前单步；结果不明、部分失败禁止继续，无额外回读。 */
class JoinTaskCleanupProtocolTest {
    private final AccountProtocolLookupService accounts = mock(AccountProtocolLookupService.class);
    private final FixedAccountGroupMetadataPort metadata = mock(FixedAccountGroupMetadataPort.class);
    private final GroupParticipantPort participants = mock(GroupParticipantPort.class);
    private final GroupLeavePort leave = mock(GroupLeavePort.class);
    private final JoinTaskCleanupProtocol service = new JoinTaskCleanupProtocol(accounts, metadata, participants, leave);
    private final ProtocolAccountRef newAccount = new ProtocolAccountRef(30L, ProtocolBackend.WEB, "new", "11111");
    private final ProtocolAccountRef original = new ProtocolAccountRef(40L, ProtocolBackend.ANDROID, "old", "22222");
    private JoinTaskResult result;
    private JoinTaskCleanup cleanup;
    private JoinTaskCleanupContext context;

    @BeforeEach
    void setup() {
        result = new JoinTaskResult(); result.setAccountId(30L); result.setAdminActorAccountId(40L);
        result.setGroupJid("123@g.us");
        cleanup = new JoinTaskCleanup();
        context = new JoinTaskCleanupContext(List.of(member("3@lid", "33333", true),
                member("4@lid", "44444", true)), 0, "11111", "22222", false);
        cleanup.setContextJson(context.toJson());
        when(accounts.findOnlineProtocolRefs(List.of(30L))).thenReturn(List.of(newAccount));
        when(accounts.findOnlineProtocolRefs(List.of(40L))).thenReturn(List.of(original));
    }

    @Test
    void originalAlreadyAbsentSkipsLeaveAfterPersistingPlanWithoutAnotherQuery() {
        cleanup.setStatus(JoinTaskCleanupStatus.LISTING.code());
        when(metadata.getMetadata(newAccount, "123@g.us")).thenReturn(snapshot(true,
                List.of(member("1@lid", "11111", true))));
        var plan = service.execute(new JoinTaskCleanupWork(cleanup, result));
        assertTrue(plan.targets().isEmpty());
        cleanup.setContextJson(plan.toJson());
        cleanup.setStatus(JoinTaskCleanupStatus.LEAVING.code());
        clearInvocations(accounts);
        service.execute(new JoinTaskCleanupWork(cleanup, result));
        verifyNoInteractions(accounts, participants, leave);
        verify(metadata, times(1)).getMetadata(newAccount, "123@g.us");
    }

    @Test
    void absentOriginalDoesNotSkipRemainingAdmins() {
        cleanup.setStatus(JoinTaskCleanupStatus.LISTING.code());
        when(metadata.getMetadata(newAccount, "123@g.us")).thenReturn(snapshot(true, List.of(
                member("1@lid", "11111", true), member("3@lid", "33333", true))));
        var plan = service.execute(new JoinTaskCleanupWork(cleanup, result));
        assertEquals(List.of("3@lid"), plan.targets().stream().map(GroupParticipantResult::jid).toList());
        verifyNoInteractions(participants, leave);
    }

    @Test
    void emptyTargetsStillLeaveWhenOriginalIsPresent() {
        cleanup.setStatus(JoinTaskCleanupStatus.LISTING.code());
        when(metadata.getMetadata(newAccount, "123@g.us")).thenReturn(snapshot(true, List.of(
                member("1@lid", "11111", true), member("2@lid", "22222", true))));
        var plan = service.execute(new JoinTaskCleanupWork(cleanup, result));
        assertTrue(plan.targets().isEmpty());
        assertFalse(plan.originalAlreadyAbsent());
        cleanup.setContextJson(plan.toJson());
        cleanup.setStatus(JoinTaskCleanupStatus.LEAVING.code());
        service.execute(new JoinTaskCleanupWork(cleanup, result));
        verify(leave).leave(original, "123@g.us");
        verify(metadata, times(1)).getMetadata(newAccount, "123@g.us");
        verifyNoInteractions(participants);
    }

    @Test
    void oldPersistedPlanStillRequiresLeaveAndAdvancePreservesAbsentFlag() {
        var legacy = JoinTaskCleanupContext.parse("""
                {"targets":[],"completed":0,"newPhone":"11111","originalPhone":"22222"}
                """);
        assertFalse(legacy.originalAlreadyAbsent());
        var absent = new JoinTaskCleanupContext(context.targets(), 0, "11111", "22222", true);
        assertTrue(JoinTaskCleanupContext.parse(absent.advance().toJson()).originalAlreadyAbsent());
    }

    @Test
    void listsOnceWithoutRecheckingNewAdminRoleAndDoesNotProtectOtherControlledAccounts() {
        cleanup.setStatus(JoinTaskCleanupStatus.LISTING.code());
        // 新号 admin=false 也不额外复核提权；提权门禁由已有阶段负责。
        when(metadata.getMetadata(newAccount, "123@g.us")).thenReturn(snapshot(true, List.of(
                member("1@lid", "11111", false), member("2@lid", "22222", true),
                member("3@lid", "33333", true), member("4@lid", "44444", false))));
        var plan = service.execute(new JoinTaskCleanupWork(cleanup, result));
        assertEquals(List.of("3@lid"), plan.targets().stream().map(GroupParticipantResult::jid).toList());
        verify(metadata, times(1)).getMetadata(newAccount, "123@g.us");
        verifyNoInteractions(participants, leave);
        verify(accounts, never()).findActiveProtocolRefsByPhones(anyList());
    }

    @Test
    void removalUsesNewAccountAndSingleTargetWithoutReadbackOrLeaving() {
        cleanup.setStatus(JoinTaskCleanupStatus.REMOVING.code());
        when(participants.updateParticipants(newAccount, "123@g.us", List.of("3@lid"), GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(new GroupParticipantBatchResult.Item("3@lid", "OK", "200"))));
        assertEquals(1, service.execute(new JoinTaskCleanupWork(cleanup, result)).completed());
        verifyNoInteractions(metadata, leave);
        verify(participants, times(1)).updateParticipants(newAccount, "123@g.us", List.of("3@lid"), GroupParticipantAction.REMOVE);
    }

    @Test
    void secondTargetFailureNeverSendsThirdOrLeaveAndMissingReceiptIsNotSuccess() {
        cleanup.setStatus(JoinTaskCleanupStatus.REMOVING.code());
        cleanup.setContextJson(context.advance().toJson());
        when(participants.updateParticipants(newAccount, "123@g.us", List.of("4@lid"), GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(true, List.of(new GroupParticipantBatchResult.Item("4@lid", "FAILED", "403"))))
                .thenReturn(new GroupParticipantBatchResult(true, List.of()));
        var error = assertThrows(IllegalArgumentException.class, () -> service.execute(new JoinTaskCleanupWork(cleanup, result)));
        assertTrue(error.getMessage().contains("403"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(new JoinTaskCleanupWork(cleanup, result)));
        verifyNoInteractions(metadata, leave);
        assertEquals(1, JoinTaskCleanupContext.parse(cleanup.getContextJson()).completed());
    }

    @Test
    void finalLeaveUsesOriginalAccountWithoutMetadata() {
        cleanup.setStatus(JoinTaskCleanupStatus.LEAVING.code());
        service.execute(new JoinTaskCleanupWork(cleanup, result));
        verify(leave).leave(original, "123@g.us");
        verifyNoInteractions(metadata, participants);
    }

    @Test
    void missingOriginalOrIncompleteListStopsBeforeRemoval() {
        cleanup.setStatus(JoinTaskCleanupStatus.LISTING.code());
        result.setAdminActorAccountId(null);
        assertThrows(IllegalArgumentException.class, () -> service.execute(new JoinTaskCleanupWork(cleanup, result)));
        result.setAdminActorAccountId(40L);
        when(metadata.getMetadata(newAccount, "123@g.us")).thenReturn(snapshot(false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.execute(new JoinTaskCleanupWork(cleanup, result)));
        verifyNoInteractions(participants, leave);
    }

    private GroupParticipantResult member(String jid, String phone, boolean admin) {
        return new GroupParticipantResult(jid, phone + "@s.whatsapp.net", phone, admin, false, "");
    }
    private GroupMetadataResult snapshot(boolean complete, List<GroupParticipantResult> members) {
        return new GroupMetadataResult("123@g.us", "group", "", null, null, null, complete,
                false, false, true, false, 0, true, true, null, false, true, members);
    }
}
