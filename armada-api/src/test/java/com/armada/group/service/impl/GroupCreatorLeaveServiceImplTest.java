package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupCreatorLeaveStatus;
import com.armada.group.model.vo.GroupCreatorLeaveAccount;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupCreatorLeaveServiceImplTest {

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupParticipantPort participantPort;

    @Mock
    private GroupLeavePort leavePort;

    private GroupCreatorLeaveServiceImpl service;

    @BeforeEach
    void setUp() {
        DataScopeContext.open(DataScope.all(1L));
        GroupLink link = new GroupLink();
        link.setId(91L);
        link.setOwnerUserId(1L);
        org.mockito.Mockito.lenient().when(groupLinkMapper.selectActiveById(
                eq(91L), any(DataScope.class))).thenReturn(link);
        service = new GroupCreatorLeaveServiceImpl(
                membershipMapper, groupLinkMapper, participantPort, leavePort);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void historicalUnownedGroupCannotLeaveEvenForAdministrator() {
        GroupLink unowned = new GroupLink();
        unowned.setId(91L);
        when(groupLinkMapper.selectActiveById(eq(91L), any(DataScope.class)))
                .thenReturn(unowned);

        var capability = service.capability(91L);
        assertThat(capability.executable()).isFalse();
        assertThat(capability.blockedReasonCode()).isEqualTo("DATA_OWNER_MISSING");

        assertThatThrownBy(() -> service.execute(91L, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        verify(membershipMapper, never()).selectCreatorLeaveAccounts(91L);
        verify(leavePort, never()).leave(any(), any());
    }

    @Test
    void offlineNormalAdminCanReceiveOwnershipWithoutPromotion() {
        GroupCreatorLeaveAccount owner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        GroupCreatorLeaveAccount offlineAdmin = account(
                12L, "admin", "admin@s.whatsapp.net", 2,
                AccountLoginStateCode.OFFLINE, AccountStateCode.NORMAL, 200L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(owner, offlineAdmin));

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.SUCCESS);
        verify(participantPort, never()).updateParticipants(
                any(ProtocolAccountRef.class), any(), any(), any());
        verify(leavePort).leave(owner.protocolRef(), "120363creator@g.us");
    }

    @Test
    void offlineAdminWinsOverOnlineOrdinaryMemberAndLeavesDirectly() {
        GroupCreatorLeaveAccount owner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        GroupCreatorLeaveAccount onlineMember = account(
                12L, "member", "member@s.whatsapp.net", 1,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 150L);
        GroupCreatorLeaveAccount offlineAdmin = account(
                13L, "admin", "admin@s.whatsapp.net", 2,
                AccountLoginStateCode.OFFLINE, AccountStateCode.NORMAL, 200L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(owner, onlineMember, offlineAdmin));

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.SUCCESS);
        verify(participantPort, never()).updateParticipants(
                any(ProtocolAccountRef.class), any(), any(), any());
        verify(leavePort).leave(owner.protocolRef(), "120363creator@g.us");
    }

    @Test
    void promotesNormalMemberBeforeOwnerLeaves() {
        GroupCreatorLeaveAccount owner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        GroupCreatorLeaveAccount member = account(
                12L, "member", "member@s.whatsapp.net", 1,
                AccountLoginStateCode.OFFLINE, AccountStateCode.NORMAL, 200L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(owner, member));
        when(participantPort.updateParticipants(
                eq(owner.protocolRef()),
                eq("120363creator@g.us"),
                eq(List.of("member@s.whatsapp.net")),
                eq(GroupParticipantAction.PROMOTE)))
                .thenReturn(new GroupParticipantBatchResult(
                        false,
                        List.of(new GroupParticipantBatchResult.Item(
                                "member@s.whatsapp.net", "OK", "200"))));

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.SUCCESS);
        verify(leavePort).leave(owner.protocolRef(), "120363creator@g.us");
    }

    @Test
    void partialPromotionResponsePreventsOwnerLeave() {
        GroupCreatorLeaveAccount owner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        GroupCreatorLeaveAccount member = account(
                12L, "member", "member@s.whatsapp.net", 1,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 200L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(owner, member));
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), any(), any(), any()))
                .thenReturn(new GroupParticipantBatchResult(true, List.of()));

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.PROMOTION_FAILED);
        verify(leavePort, never()).leave(any(), any());
    }

    @Test
    void capabilityIsBlockedWhenNoControlledCandidateExists() {
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(account(
                        11L, "owner", "owner@s.whatsapp.net", 3,
                        AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L)));

        var capability = service.capability(91L);

        assertThat(capability.executable()).isFalse();
        assertThat(capability.blockedReasonCode()).isEqualTo("NO_AVAILABLE_CONTROLLER");
    }

    @Test
    void preferredCreatorMustBeCurrentOwner() {
        GroupCreatorLeaveAccount currentOwner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        GroupCreatorLeaveAccount preferredButMember = account(
                12L, "preferred", "preferred@s.whatsapp.net", 1,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 200L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(currentOwner, preferredButMember));

        var result = service.execute(91L, 12L);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.NOT_CREATOR);
        verify(participantPort, never()).updateParticipants(
                any(ProtocolAccountRef.class), any(), any(), any());
        verify(leavePort, never()).leave(any(), any());
    }

    @Test
    void offlineOwnerCannotSendCreatorLeaveRequest() {
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(
                        account(11L, "owner", "owner@s.whatsapp.net", 3,
                                AccountLoginStateCode.OFFLINE, AccountStateCode.NORMAL, 100L),
                        account(12L, "admin", "admin@s.whatsapp.net", 2,
                                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 200L)));

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.CREATOR_UNAVAILABLE);
        verify(leavePort, never()).leave(any(), any());
    }

    @Test
    void leaveProtocolFailureIsReportedAfterAdminHandover() {
        GroupCreatorLeaveAccount owner = account(
                11L, "owner", "owner@s.whatsapp.net", 3,
                AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 100L);
        when(membershipMapper.selectCreatorLeaveAccounts(91L))
                .thenReturn(List.of(owner, account(
                        12L, "admin", "admin@s.whatsapp.net", 2,
                        AccountLoginStateCode.OFFLINE, AccountStateCode.NORMAL, 200L)));
        org.mockito.Mockito.doThrow(ProtocolException.unknown("leave failed", null))
                .when(leavePort).leave(owner.protocolRef(), "120363creator@g.us");

        var result = service.execute(91L, null);

        assertThat(result.status()).isEqualTo(GroupCreatorLeaveStatus.LEAVE_FAILED);
    }

    private GroupCreatorLeaveAccount account(
            Long accountId,
            String protocolAccountId,
            String participantJid,
            int role,
            int loginState,
            int accountState,
            Long activeSince) {
        return new GroupCreatorLeaveAccount(
                accountId,
                "WEB",
                protocolAccountId,
                protocolAccountId,
                "120363creator@g.us",
                participantJid,
                role,
                loginState,
                accountState,
                activeSince);
    }
}
