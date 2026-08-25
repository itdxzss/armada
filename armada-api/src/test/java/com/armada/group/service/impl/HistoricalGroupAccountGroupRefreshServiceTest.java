package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.vo.AccountGroupCompatibilitySnapshot;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoricalGroupAccountGroupRefreshServiceTest {

    @Mock private AccountGroupMapper accountGroupMapper;
    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private AccountParticipatingGroupPort participatingGroups;
    @Mock private FixedAccountGroupMetadataPort readMetadata;
    @Mock private GroupMetadataPort writeMetadata;
    @Mock private GroupInvitePort invitePort;
    @Mock private GroupParticipantPort participantPort;
    @Mock private AccountGroupMembershipSnapshotService snapshotService;
    @Mock private AccountGroupMembershipReportPhaseService reportPhaseService;
    @Mock private GroupInviteLinkService inviteLinkService;

    private HistoricalGroupAccountGroupRefreshService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(snapshotService.prepareVisibleGroups(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ProtocolBackend.class)))
                .thenReturn(new AccountGroupCompatibilitySnapshot(
                        List.of(), GroupClassificationPlan.empty()));
        service = new HistoricalGroupAccountGroupRefreshService(
                accountGroupMapper,
                accountLookupService,
                new HistoricalGroupProtocolPorts(
                        participatingGroups,
                        readMetadata,
                        writeMetadata,
                        invitePort,
                        participantPort),
                snapshotService,
                reportPhaseService,
                inviteLinkService);
    }

    @Test
    void refreshesEveryOnlineAccountAndFetchesInviteOnceForAdminGroup() {
        AccountGroup accountGroup = new AccountGroup();
        accountGroup.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(accountGroup);
        ProtocolAccountRef first = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "web-1", "8611");
        ProtocolAccountRef second = new ProtocolAccountRef(
                2L, ProtocolBackend.ANDROID, "android-2", "8622");
        when(accountLookupService.findOnlineNormalByGroupId(12L))
                .thenReturn(List.of(first, second));
        AccountParticipatingGroupResult.Group adminGroup =
                new AccountParticipatingGroupResult.Group(
                        "120363admin@g.us", "管理群", 20,
                        "8699@s.whatsapp.net", "8699", OwnerIdentityKind.PN,
                        true, false, 1720000000L);
        AccountParticipatingGroupResult.Group memberGroup =
                new AccountParticipatingGroupResult.Group(
                        "120363member@g.us", "成员群", 10,
                        "193088878297313@lid", null, OwnerIdentityKind.LID,
                        false, true, 1710000000L);
        when(participatingGroups.listCurrent(first))
                .thenReturn(List.of(adminGroup, memberGroup));
        when(participatingGroups.listCurrent(second))
                .thenReturn(List.of(adminGroup));
        when(invitePort.getInvite(first, "120363admin@g.us"))
                .thenReturn(new GroupInviteResult(
                        "120363admin@g.us", "InviteCode",
                        "https://chat.whatsapp.com/InviteCode"));

        service.refresh(12L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountGroupsReportedEvent.Group>> groupsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotService, times(2)).prepareVisibleGroups(
                org.mockito.ArgumentMatchers.anyLong(),
                groupsCaptor.capture(),
                eq(true),
                anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"),
                org.mockito.ArgumentMatchers.any(ProtocolBackend.class));
        verify(reportPhaseService).applyManualCurrentSnapshot(
                eq(1L), eq(groupsCaptor.getAllValues().get(0)),
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AccountGroupCompatibilitySnapshot.class));
        verify(reportPhaseService).applyManualCurrentSnapshot(
                eq(2L), eq(groupsCaptor.getAllValues().get(1)),
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AccountGroupCompatibilitySnapshot.class));
        org.assertj.core.api.Assertions.assertThat(groupsCaptor.getAllValues().get(0))
                .satisfiesExactly(
                        group -> {
                            org.assertj.core.api.Assertions.assertThat(group.ownerJid())
                                    .isEqualTo("8699@s.whatsapp.net");
                            org.assertj.core.api.Assertions.assertThat(group.ownerPhone())
                                    .isEqualTo("8699");
                        },
                        group -> {
                            org.assertj.core.api.Assertions.assertThat(group.ownerJid())
                                    .isEqualTo("193088878297313@lid");
                            org.assertj.core.api.Assertions.assertThat(group.ownerPhone()).isNull();
                        });
        verify(invitePort, times(1)).getInvite(first, "120363admin@g.us");
        ArgumentCaptor<GroupInviteLinkObservation> observation =
                ArgumentCaptor.forClass(GroupInviteLinkObservation.class);
        verify(inviteLinkService).applyCurrentInvite(observation.capture());
        org.assertj.core.api.Assertions.assertThat(observation.getValue()).satisfies(value -> {
            org.assertj.core.api.Assertions.assertThat(value.groupJid())
                    .isEqualTo("120363admin@g.us");
            org.assertj.core.api.Assertions.assertThat(value.inviteCode())
                    .isEqualTo("InviteCode");
            org.assertj.core.api.Assertions.assertThat(value.protocolBackend())
                    .isEqualTo(ProtocolBackend.WEB);
            org.assertj.core.api.Assertions.assertThat(value.source())
                    .isEqualTo("HISTORICAL_GROUP_MANUAL_REFRESH");
        });
    }

    @Test
    void isolatesOneAccountFailureAndPersistsTheOtherAccountSnapshot() {
        AccountGroup accountGroup = new AccountGroup();
        accountGroup.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(accountGroup);
        ProtocolAccountRef failed = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "web-1", "8611");
        ProtocolAccountRef healthy = new ProtocolAccountRef(
                2L, ProtocolBackend.ANDROID, "android-2", "8622");
        when(accountLookupService.findOnlineNormalByGroupId(12L))
                .thenReturn(List.of(failed, healthy));
        when(participatingGroups.listCurrent(failed))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.TIMEOUT,
                        "first account timed out"));
        when(participatingGroups.listCurrent(healthy)).thenReturn(List.of(
                new AccountParticipatingGroupResult.Group(
                        "120363healthy@g.us", "可用管理群", 20,
                        "8622@s.whatsapp.net", "8622", OwnerIdentityKind.PN,
                        true, false, 1720000000L)));
        when(invitePort.getInvite(healthy, "120363healthy@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_UNAVAILABLE,
                        "invite unavailable"));

        service.refresh(12L);

        verify(snapshotService).prepareVisibleGroups(
                eq(2L),
                org.mockito.ArgumentMatchers.anyList(),
                eq(true),
                anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"),
                eq(ProtocolBackend.ANDROID));
        verify(snapshotService, times(0)).prepareVisibleGroups(
                eq(1L),
                org.mockito.ArgumentMatchers.anyList(),
                eq(true),
                anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"),
                eq(ProtocolBackend.WEB));
        verify(inviteLinkService, times(0)).applyCurrentInvite(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isolatesOneAccountPersistenceFailureAndContinuesWithTheOtherAccount() {
        AccountGroup accountGroup = new AccountGroup();
        accountGroup.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(accountGroup);
        ProtocolAccountRef failed = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "web-1", "8611");
        ProtocolAccountRef healthy = new ProtocolAccountRef(
                2L, ProtocolBackend.ANDROID, "android-2", "8622");
        when(accountLookupService.findOnlineNormalByGroupId(12L))
                .thenReturn(List.of(failed, healthy));
        when(participatingGroups.listCurrent(failed)).thenReturn(List.of(
                new AccountParticipatingGroupResult.Group(
                        "120363failed@g.us", "写库失败群", 10,
                        "8611@s.whatsapp.net", "8611", OwnerIdentityKind.PN,
                        true, false, 1720000000L)));
        when(participatingGroups.listCurrent(healthy)).thenReturn(List.of(
                new AccountParticipatingGroupResult.Group(
                        "120363healthy@g.us", "可用管理群", 20,
                        "8622@s.whatsapp.net", "8622", OwnerIdentityKind.PN,
                        true, false, 1720000000L)));
        when(snapshotService.prepareVisibleGroups(
                eq(1L),
                org.mockito.ArgumentMatchers.anyList(),
                eq(true),
                anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"),
                eq(ProtocolBackend.WEB)))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.refresh(12L);

        verify(snapshotService).prepareVisibleGroups(
                eq(2L),
                org.mockito.ArgumentMatchers.anyList(),
                eq(true),
                anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"),
                eq(ProtocolBackend.ANDROID));
        verify(invitePort, times(0)).getInvite(failed, "120363failed@g.us");
    }

    @Test
    void currentModelWriteFailureDoesNotCountAccountAsSuccessfullyRefreshed() {
        AccountGroup accountGroup = new AccountGroup();
        accountGroup.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(accountGroup);
        ProtocolAccountRef account = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "web-1", "8611");
        when(accountLookupService.findOnlineNormalByGroupId(12L))
                .thenReturn(List.of(account));
        when(participatingGroups.listCurrent(account)).thenReturn(List.of(
                new AccountParticipatingGroupResult.Group(
                        "120363admin@g.us", "管理群", 10,
                        "8611@s.whatsapp.net", "8611", OwnerIdentityKind.PN,
                        true, false, 1720000000L)));
        doThrow(new IllegalStateException("current model unavailable"))
                .when(reportPhaseService)
                .applyManualCurrentSnapshot(
                        eq(1L), org.mockito.ArgumentMatchers.anyList(),
                        anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(AccountGroupCompatibilitySnapshot.class));
        assertThatThrownBy(() -> service.refresh(12L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号组群列表加载失败");

        verify(snapshotService).prepareVisibleGroups(
                eq(1L), org.mockito.ArgumentMatchers.anyList(), eq(true),
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                eq("HISTORICAL_GROUP_MANUAL_REFRESH"), eq(ProtocolBackend.WEB));
        verify(inviteLinkService, times(0)).applyCurrentInvite(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isolatesOneInvitePersistenceFailureAndContinuesWithTheOtherGroup() {
        AccountGroup accountGroup = new AccountGroup();
        accountGroup.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(accountGroup);
        ProtocolAccountRef account = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "web-1", "8611");
        when(accountLookupService.findOnlineNormalByGroupId(12L))
                .thenReturn(List.of(account));
        when(participatingGroups.listCurrent(account)).thenReturn(List.of(
                new AccountParticipatingGroupResult.Group(
                        "120363first@g.us", "第一群", 10,
                        "8611@s.whatsapp.net", "8611", OwnerIdentityKind.PN,
                        true, false, 1720000000L),
                new AccountParticipatingGroupResult.Group(
                        "120363second@g.us", "第二群", 20,
                        "8611@s.whatsapp.net", "8611", OwnerIdentityKind.PN,
                        true, false, 1720000000L)));
        when(invitePort.getInvite(account, "120363first@g.us"))
                .thenReturn(new GroupInviteResult(
                        "120363first@g.us", "FirstCode", null));
        when(invitePort.getInvite(account, "120363second@g.us"))
                .thenReturn(new GroupInviteResult(
                        "120363second@g.us", "SecondCode", null));
        doThrow(new IllegalStateException("database unavailable"))
                .when(inviteLinkService)
                .applyCurrentInvite(org.mockito.ArgumentMatchers.argThat(observation ->
                        "120363first@g.us".equals(observation.groupJid())));

        service.refresh(12L);

        verify(inviteLinkService).applyCurrentInvite(
                org.mockito.ArgumentMatchers.argThat(observation ->
                        "120363second@g.us".equals(observation.groupJid())
                                && "SecondCode".equals(observation.inviteCode())));
    }
}
