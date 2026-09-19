package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupApprovalPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskApproval;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskApprovalStage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JoinTaskApprovalProtocolTest {
    private final AccountProtocolLookupService accounts=mock(AccountProtocolLookupService.class);
    private final GroupExecutionAccountSelector selector=mock(GroupExecutionAccountSelector.class);
    private final FixedAccountGroupMetadataPort metadata=mock(FixedAccountGroupMetadataPort.class);
    private final GroupApprovalPort approvals=mock(GroupApprovalPort.class);
    private final GroupSettingsPort settings=mock(GroupSettingsPort.class);
    private final GroupJoinPort joins=mock(GroupJoinPort.class);
    private final ProtocolAccountRef target=new ProtocolAccountRef(30L,ProtocolBackend.WEB,"new","111");
    private final GroupExecutionAccount original=new GroupExecutionAccount(40L,"ANDROID","old","222",true);
    private JoinTaskApprovalProtocol service;
    private JoinTaskApprovalWork work;

    @BeforeEach
    void setup() {
        service=new JoinTaskApprovalProtocol(new JoinTaskApprovalFacts(accounts,selector,metadata),approvals,settings,joins);
        var task=new JoinTask();task.setOwnerUserId(90L);
        var result=new JoinTaskResult();result.setId(20L);result.setTenantId(7L);result.setAccountId(30L);
        result.setLink("https://chat.whatsapp.com/abc");
        var state=new JoinTaskApproval();state.setResultId(20L);state.setTargetPhone("111");
        state.setTargetProtocolAccountId("new");state.setActorAccountId(40L);state.setActorPhone("222");
        state.setGroupJid("120@g.us");state.setVersion(1);
        work=new JoinTaskApprovalWork(task,result,state);
        when(accounts.findActiveProtocolRef(30L)).thenReturn(Optional.of(target));
        when(selector.findJoinTaskAdminCandidates(7L,"120@g.us",30L,90L)).thenReturn(List.of(original));
    }
    private JoinTaskApprovalStage execute(JoinTaskApprovalStage stage) {
        work.approval().setStage(stage.code());return service.execute(work);
    }
    private GroupMetadataResult snapshot(boolean targetPresent, boolean complete) {
        var snapshot=mock(GroupMetadataResult.class);
        when(snapshot.groupJid()).thenReturn("120@g.us");
        when(snapshot.participantsComplete()).thenReturn(complete);
        var admin=new GroupParticipantResult("222@s.whatsapp.net",null,"222",true,false,"admin");
        var member=new GroupParticipantResult("77@lid","111@s.whatsapp.net","111",false,false,"");
        when(snapshot.participants()).thenReturn(targetPresent?List.of(admin,member):List.of(admin));
        when(metadata.getMetadata(original.protocolRef(),"120@g.us")).thenReturn(snapshot);
        return snapshot;
    }
    @Test
    void resolvesGroupOnlyInsideRecoveryAndSelectsOriginalAdminWithoutRequiringTargetMembership() {
        work.approval().setGroupJid("");snapshot(false,true);
        when(approvals.resolveGroup(target,work.result().getLink())).thenReturn("120@g.us");
        assertEquals(JoinTaskApprovalStage.CLOSE,execute(JoinTaskApprovalStage.RESOLVE));
        assertEquals(40L,work.approval().getActorAccountId());
        verifyNoInteractions(settings,joins);
    }
    @Test
    void closeUsesOriginalAccountProtocolAndDoesNotMarkJoinedOrApproveYet() {
        assertEquals(JoinTaskApprovalStage.CHECK,execute(JoinTaskApprovalStage.CLOSE));
        verify(settings).setJoinApprovalEnabled(original.protocolRef(),"120@g.us",false);
        verifyNoInteractions(approvals,joins);
    }
    @Test
    void existingMemberCompletesWithoutAnotherJoinOrApproval() {
        snapshot(true,true);
        assertEquals(JoinTaskApprovalStage.SUCCESS,execute(JoinTaskApprovalStage.CHECK));
        verifyNoInteractions(approvals,joins);
    }
    @Test
    void onlyCurrentPendingAccountIsApprovedThenMembershipMustBeVerified() {
        snapshot(false,true);
        when(approvals.pending(original.protocolRef(),"120@g.us")).thenReturn(List.of("111@s.whatsapp.net","999@s.whatsapp.net"));
        assertEquals(JoinTaskApprovalStage.APPROVE,execute(JoinTaskApprovalStage.CHECK));
        assertEquals(JoinTaskApprovalStage.VERIFY,execute(JoinTaskApprovalStage.APPROVE));
        verify(approvals).approve(original.protocolRef(),"120@g.us","111@s.whatsapp.net");
        verifyNoInteractions(joins);
        assertEquals(JoinTaskApprovalStage.VERIFY,execute(JoinTaskApprovalStage.VERIFY));
        snapshot(true,true);
        assertEquals(JoinTaskApprovalStage.SUCCESS,execute(JoinTaskApprovalStage.VERIFY));
    }
    @Test
    void confirmedAbsenceAllowsOneContinuationThenOnlyReadOnlyVerification() {
        snapshot(false,true);when(approvals.pending(original.protocolRef(),"120@g.us")).thenReturn(List.of());
        assertEquals(JoinTaskApprovalStage.REJOIN,execute(JoinTaskApprovalStage.CHECK));
        assertEquals(JoinTaskApprovalStage.VERIFY,execute(JoinTaskApprovalStage.REJOIN));
        assertEquals(JoinTaskApprovalStage.VERIFY,execute(JoinTaskApprovalStage.VERIFY));
        verify(joins,times(1)).join(argThat(cmd -> cmd.account().equals(target)));
    }
    @Test
    void incompleteMembersOrUnknownLidDoNotResendJoin() {
        snapshot(false,false);
        assertEquals(JoinTaskApprovalStage.CHECK,execute(JoinTaskApprovalStage.CHECK));
        verifyNoInteractions(approvals,joins);
        snapshot(false,true);when(approvals.pending(original.protocolRef(),"120@g.us")).thenReturn(List.of("9@lid"));
        assertEquals(JoinTaskApprovalStage.CHECK,execute(JoinTaskApprovalStage.CHECK));
        verifyNoInteractions(joins);
    }
    @Test
    void unresolvedMemberIdentityCannotProveTargetAbsentAndIncompleteAdminIsNotPermissionDenied() {
        var snapshot=snapshot(false,true);
        when(snapshot.participants()).thenReturn(List.of(new GroupParticipantResult("77@lid",null,null,false,false,"")));
        when(approvals.pending(original.protocolRef(),"120@g.us")).thenReturn(List.of());
        assertEquals(JoinTaskApprovalStage.CHECK,execute(JoinTaskApprovalStage.CHECK));
        verifyNoInteractions(joins);
        when(snapshot.participantsComplete()).thenReturn(false);
        var error=assertThrows(ProtocolException.class,()->execute(JoinTaskApprovalStage.RESOLVE));
        assertEquals(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,error.errorCode());
    }
    @Test
    void lostOwnershipOrTargetIdentityStopsBeforeAnyMutation() {
        when(selector.findJoinTaskAdminCandidates(7L,"120@g.us",30L,90L)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,()->execute(JoinTaskApprovalStage.CLOSE));
        verifyNoInteractions(settings,approvals,joins);
        when(accounts.findActiveProtocolRef(30L)).thenReturn(Optional.of(new ProtocolAccountRef(30L,ProtocolBackend.WEB,"new","333")));
        assertThrows(IllegalArgumentException.class,()->execute(JoinTaskApprovalStage.REJOIN));
        verifyNoInteractions(joins);
    }
    @Test
    void permissionAndGroupFailureHaveExactUserReceiptsAndTimeoutDoesNotPretendDenied() {
        assertEquals("关闭群组审核失败：无管理员权限", service.failure(JoinTaskApprovalStage.CLOSE,
                new ProtocolException(ProtocolErrorCode.GROUP_PERMISSION_DENIED,"redacted")));
        assertEquals("关闭群组审核失败：群组状态异常",service.failure(JoinTaskApprovalStage.CLOSE,
                new ProtocolException(ProtocolErrorCode.GROUP_UNAVAILABLE,"redacted")));
        assertEquals("关闭群组审核结果未确认：请求超时",service.failure(JoinTaskApprovalStage.CLOSE,
                new ProtocolException(ProtocolErrorCode.TIMEOUT,"redacted")));
    }
}
