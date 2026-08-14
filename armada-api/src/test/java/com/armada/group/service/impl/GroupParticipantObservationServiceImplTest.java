package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.dto.WhatsappGroupMemberStateWrite;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群成员增量观察事实收敛服务测试。 */
@ExtendWith(MockitoExtension.class)
class GroupParticipantObservationServiceImplTest {

    @Mock private WhatsappGroupMemberCacheMapper memberStateMapper;
    @Mock private WhatsappGroupMemberSnapshotMapper memberSnapshotMapper;
    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountGroupMembershipMapper membershipMapper;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void promoteUsesWinningMemberStateToUpdateSnapshotAndControlledMembership() {
        GroupParticipantObservation input = observation(
                "123456789012345@lid", "+1 555 000 0001", true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 1_000L, "event-1");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("123456789012345@lid")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "123456789012345@lid", "15550000001", true, false,
                        "admin", true, "ROLE_EVENT", 1_000L)));
        when(groupLinkMapper.selectActiveIdByGroupJid("120363-test@g.us")).thenReturn(99L);
        when(memberSnapshotMapper.selectByGroupLinkId(99L)).thenReturn(List.of(
                snapshot("123456789012345@lid", "15550000001")));
        when(accountMapper.selectActiveByWsPhones(List.of("15550000001")))
                .thenReturn(List.of(account(77L, "15550000001")));
        when(membershipMapper.selectCurrentStatuses(List.of(
                new AccountGroupMembershipLookup(77L, "120363-test@g.us"))))
                .thenReturn(List.of());

        service().apply(List.of(input));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupMemberStateWrite>> stateCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(memberStateMapper).upsertStates(stateCaptor.capture(), anyLong());
        assertThat(stateCaptor.getValue()).singleElement().satisfies(state -> {
            assertThat(state.tenantId()).isEqualTo(7L);
            assertThat(state.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(state.participantJid()).isEqualTo("123456789012345@lid");
            assertThat(state.phone()).isEqualTo("15550000001");
            assertThat(state.admin()).isTrue();
            assertThat(state.inGroup()).isTrue();
            assertThat(state.stateSource()).isEqualTo("ROLE_EVENT");
            assertThat(state.sourceEventId()).isEqualTo("event-1");
        });
        verify(memberSnapshotMapper).updateAdminRole(
                99L, List.of("123456789012345@lid"), true, 1_000L);
        ArgumentCaptor<AccountGroupMembership> membershipCaptor =
                ArgumentCaptor.forClass(AccountGroupMembership.class);
        verify(membershipMapper).upsertMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue()).satisfies(row -> {
            assertThat(row.getAccountId()).isEqualTo(77L);
            assertThat(row.getGroupLinkId()).isEqualTo(99L);
            assertThat(row.getAdmin()).isTrue();
            assertThat(row.getMembershipStatus()).isEqualTo(AccountGroupMembershipStatus.IN_GROUP.code());
            assertThat(row.getStatusSource()).isEqualTo("WGP2_PROMOTE");
            assertThat(row.getStatusUpdatedAt()).isEqualTo(1_000L);
        });
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void stalePromoteAppliesNewerWinningDemoteInsteadOfInput() {
        GroupParticipantObservation input = observation(
                "15550000001@s.whatsapp.net", "15550000001", true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 1_000L, "old-promote");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("15550000001@s.whatsapp.net")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "15550000001@s.whatsapp.net", "15550000001", false, false,
                        "member", true, "ROLE_EVENT", 2_000L)));
        when(groupLinkMapper.selectActiveIdByGroupJid("120363-test@g.us")).thenReturn(99L);
        when(memberSnapshotMapper.selectByGroupLinkId(99L)).thenReturn(List.of(
                snapshot("15550000001@s.whatsapp.net", "15550000001")));
        when(accountMapper.selectActiveByWsPhones(List.of("15550000001")))
                .thenReturn(List.of(account(77L, "15550000001")));
        when(membershipMapper.selectCurrentStatuses(List.of(
                new AccountGroupMembershipLookup(77L, "120363-test@g.us"))))
                .thenReturn(List.of());

        service().apply(List.of(input));

        verify(memberSnapshotMapper).updateAdminRole(
                99L, List.of("15550000001@s.whatsapp.net"), false, 2_000L);
        ArgumentCaptor<AccountGroupMembership> membershipCaptor =
                ArgumentCaptor.forClass(AccountGroupMembership.class);
        verify(membershipMapper).upsertMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getAdmin()).isFalse();
        assertThat(membershipCaptor.getValue().getStatusSource()).isEqualTo("WGP2_DEMOTE");
        assertThat(membershipCaptor.getValue().getStatusUpdatedAt()).isEqualTo(2_000L);
    }

    @Test
    void memberQueryNotInGroupDeletesSnapshotAndPreservesExistingExitStatus() {
        GroupParticipantObservation input = observation(
                null, null, false, false,
                WhatsappGroupMemberStateSource.MEMBER_QUERY, 3_000L, "query-1");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("15550000001@s.whatsapp.net")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "15550000001@s.whatsapp.net", "15550000001", false, false,
                        "member", false, "MEMBER_QUERY", 3_000L)));
        when(groupLinkMapper.selectActiveIdByGroupJid("120363-test@g.us")).thenReturn(99L);
        when(memberSnapshotMapper.selectByGroupLinkId(99L)).thenReturn(List.of(
                snapshot("123456789012345@lid", "15550000001")));
        when(accountMapper.selectActiveByWsPhones(List.of("15550000001")))
                .thenReturn(List.of(account(77L, "15550000001")));
        when(membershipMapper.selectCurrentStatuses(List.of(
                new AccountGroupMembershipLookup(77L, "120363-test@g.us"))))
                .thenReturn(List.of(new AccountGroupMembershipStatusRow(
                        77L, "120363-test@g.us", AccountGroupMembershipStatus.LEFT.code(), 2_000L)));

        service().apply(List.of(input));

        verify(memberSnapshotMapper).deleteParticipants(
                99L, List.of("123456789012345@lid"));
        ArgumentCaptor<AccountGroupMembership> membershipCaptor =
                ArgumentCaptor.forClass(AccountGroupMembership.class);
        verify(membershipMapper).upsertMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getAdmin()).isFalse();
        assertThat(membershipCaptor.getValue().getMembershipStatus())
                .isEqualTo(AccountGroupMembershipStatus.LEFT.code());
        assertThat(membershipCaptor.getValue().getStatusSource()).isEqualTo("GROUP_MEMBER_QUERY");
    }

    @Test
    void unresolvedLidStoresMemberFactWithoutCreatingControlledMembership() {
        GroupParticipantObservation input = observation(
                "123456789012345@lid", null, true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 1_000L, "event-lid");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("123456789012345@lid")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "123456789012345@lid", null, true, false,
                        "admin", true, "ROLE_EVENT", 1_000L)));
        when(groupLinkMapper.selectActiveIdByGroupJid("120363-test@g.us")).thenReturn(99L);
        when(memberSnapshotMapper.selectByGroupLinkId(99L)).thenReturn(List.of());

        service().apply(List.of(input));

        verify(accountMapper, never()).selectActiveByWsPhones(org.mockito.ArgumentMatchers.anyList());
        verify(membershipMapper, never()).upsertMembership(org.mockito.ArgumentMatchers.any());
    }

    private GroupParticipantObservationServiceImpl service() {
        return new GroupParticipantObservationServiceImpl(
                memberStateMapper, memberSnapshotMapper, groupLinkMapper, accountMapper, membershipMapper);
    }

    private static GroupParticipantObservation observation(
            String participantJid,
            String phone,
            boolean inGroup,
            boolean admin,
            WhatsappGroupMemberStateSource source,
            long observedAt,
            String eventId) {
        return new GroupParticipantObservation(
                7L, 10L, "120363-TEST@G.US", "15550000001@s.whatsapp.net",
                participantJid, phone, inGroup, admin, source, observedAt, eventId);
    }

    private static Account account(Long id, String phone) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(7L);
        account.setWsPhone(phone);
        return account;
    }

    private static WhatsappGroupMemberSnapshot snapshot(String participantJid, String phone) {
        WhatsappGroupMemberSnapshot row = new WhatsappGroupMemberSnapshot();
        row.setGroupLinkId(99L);
        row.setGroupJid("120363-test@g.us");
        row.setParticipantJid(participantJid);
        row.setPhone(phone);
        row.setIsAdmin(false);
        row.setIsOwner(false);
        return row;
    }
}
