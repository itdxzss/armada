package com.armada.group.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class AccountGroupMembershipSnapshotServiceImplTest {

    private static final ProtocolBackend OBSERVED_BACKEND = ProtocolBackend.ANDROID;

    private final AccountGroupMembershipMapper membershipMapper =
            Mockito.mock(AccountGroupMembershipMapper.class);
    private final GroupLinkMapper groupLinkMapper = Mockito.mock(GroupLinkMapper.class);
    private final GroupLinkHealthMapper healthMapper = Mockito.mock(GroupLinkHealthMapper.class);
    private final GroupLinkRegistryService registryService = Mockito.mock(GroupLinkRegistryService.class);
    private final AccountGroupMembershipSnapshotServiceImpl service =
            new AccountGroupMembershipSnapshotServiceImpl(
                    membershipMapper, groupLinkMapper, healthMapper, registryService);

    @Test
    void replaceVisibleGroups_usesGroupJidAsNewLinkNameWhenSubjectIsMissing() {
        String groupJid = "120363000000000001@g.us";
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(groupJid),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(99L);

        service.replaceVisibleGroups(
                10L,
                List.of(new AccountGroupsReportedEvent.Group(
                        groupJid, null, null, null, null, null, null, null)),
                true,
                1783785600000L,
                "evt-light-groups",
                "wa_groups_dirty",
                OBSERVED_BACKEND);

        Mockito.verify(registryService)
                .registerAccountObservedGroup(
                        org.mockito.ArgumentMatchers.eq(groupJid),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replaceVisibleGroups_returnsOnlyGroupsMissingBeforeRefreshAsAdded() {
        when(membershipMapper.selectSnapshotEstablishedGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of("120363old@g.us"));
        when(membershipMapper.selectSendableGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of("120363old@g.us", "120363new@g.us"));
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq("120363old@g.us"),
                org.mockito.ArgumentMatchers.eq("旧群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(11L);
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq("120363new@g.us"),
                org.mockito.ArgumentMatchers.eq("新群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(12L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(
                        group("120363old@g.us", "旧群"),
                        group("120363new@g.us", "新群"),
                        group("120363new@g.us", "重复新群")),
                true,
                1783785600000L,
                "evt-added",
                "wa_groups_dirty",
                OBSERVED_BACKEND);

        org.assertj.core.api.Assertions.assertThat(result.currentGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly("120363new@g.us", "120363old@g.us");
        org.assertj.core.api.Assertions.assertThat(result.addedGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly("120363new@g.us");
    }

    @Test
    void replaceVisibleGroups_persistsAllTablesInOneGlobalLockOrder() {
        String firstJid = "120363-order-a@g.us";
        String secondJid = "120363-order-b@g.us";
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(firstJid),
                org.mockito.ArgumentMatchers.eq("A群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(20L);
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(secondJid),
                org.mockito.ArgumentMatchers.eq("B群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(10L);
        when(membershipMapper.selectExistingPreviewGroupLinkIds(List.of(10L, 20L)))
                .thenReturn(List.of());
        when(healthMapper.selectExistingGroupLinkIds(List.of(10L, 20L)))
                .thenReturn(List.of());
        when(membershipMapper.selectExistingActiveGroupJids(
                10L, List.of(firstJid, secondJid))).thenReturn(List.of());

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(group(secondJid, "B群"), group(firstJid, "A群")),
                false,
                3_000L,
                "evt-global-lock-order",
                "android_online_group_sync",
                OBSERVED_BACKEND);

        InOrder inOrder = Mockito.inOrder(registryService, membershipMapper, healthMapper);
        inOrder.verify(registryService).registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(firstJid),
                org.mockito.ArgumentMatchers.eq("A群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong());
        inOrder.verify(registryService).registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(secondJid),
                org.mockito.ArgumentMatchers.eq("B群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong());
        inOrder.verify(membershipMapper).selectExistingPreviewGroupLinkIds(List.of(10L, 20L));
        inOrder.verify(healthMapper).selectExistingGroupLinkIds(List.of(10L, 20L));
        inOrder.verify(membershipMapper).selectExistingActiveGroupJids(
                10L, List.of(firstJid, secondJid));
        verifyPreviewPersist(inOrder, 10L, secondJid);
        verifyPreviewPersist(inOrder, 20L, firstJid);
        verifyHealthPersist(inOrder, 10L);
        verifyHealthPersist(inOrder, 20L);
        verifyMembershipPersist(inOrder, firstJid);
        verifyMembershipPersist(inOrder, secondJid);
        org.assertj.core.api.Assertions.assertThat(result.currentGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly(firstJid, secondJid);
    }

    @Test
    void replaceVisibleGroups_updatesExistingSnapshotsWithoutInsertCandidateFallback() {
        String groupJid = "120363-existing-snapshot@g.us";
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(groupJid),
                org.mockito.ArgumentMatchers.eq("存量群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(30L);
        when(membershipMapper.selectExistingPreviewGroupLinkIds(List.of(30L)))
                .thenReturn(List.of(30L));
        when(healthMapper.selectExistingGroupLinkIds(List.of(30L)))
                .thenReturn(List.of(30L));
        when(membershipMapper.selectExistingActiveGroupJids(10L, List.of(groupJid)))
                .thenReturn(List.of(groupJid));
        when(membershipMapper.updatePreviewFromAccountSync(any())).thenReturn(1);
        when(healthMapper.updateFromAccountGroupSync(any())).thenReturn(1);
        when(membershipMapper.updateActiveMembership(any())).thenReturn(1);

        service.replaceVisibleGroups(
                10L,
                List.of(group(groupJid, "存量群")),
                false,
                4_000L,
                "evt-update-first",
                "android_online_group_sync",
                OBSERVED_BACKEND);

        Mockito.verify(membershipMapper).updatePreviewFromAccountSync(
                org.mockito.ArgumentMatchers.argThat(row -> row.getGroupLinkId().equals(30L)
                        && row.getGroupJid().equals(groupJid)
                        && row.getLastPreviewAt().equals(4_000L)));
        Mockito.verify(healthMapper).updateFromAccountGroupSync(
                org.mockito.ArgumentMatchers.argThat(row -> row.getGroupLinkId().equals(30L)
                        && row.getLastCheckAt().equals(4_000L)));
        Mockito.verify(membershipMapper, Mockito.never()).upsertPreviewFromAccountSync(
                any(), any(), any(), any(), any(), any(), any(), any(Long.class), any(Long.class));
        Mockito.verify(healthMapper, Mockito.never()).upsertFromAccountGroupSync(any());
        Mockito.verify(membershipMapper, Mockito.never()).upsertMembership(any());
    }

    @Test
    void staleSnapshotDoesNotReportExitedRelationshipAsAddedWhenStatusUpsertWasRejected() {
        String groupJid = "120363kicked@g.us";
        when(membershipMapper.selectSnapshotEstablishedGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of());
        when(membershipMapper.selectSendableGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of());
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(groupJid),
                org.mockito.ArgumentMatchers.eq("旧快照群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(13L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(group(groupJid, "旧快照群")),
                false,
                1_000L,
                "evt-stale",
                "android_groups_dirty",
                OBSERVED_BACKEND);

        org.assertj.core.api.Assertions.assertThat(result.addedGroups()).isEmpty();
    }

    @Test
    void firstSnapshotAfterPreciseAddStillReportsGroupAsAddedForImmediateMarketing() {
        String groupJid = "120363precise-add@g.us";
        when(membershipMapper.selectSnapshotEstablishedGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of());
        when(membershipMapper.selectSendableGroupJids(10L, List.of(1, 2)))
                .thenReturn(List.of(groupJid));
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq(groupJid),
                org.mockito.ArgumentMatchers.eq("精确新增群"),
                org.mockito.ArgumentMatchers.eq(OBSERVED_BACKEND),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(14L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(group(groupJid, "精确新增群")),
                true,
                2_000L,
                "evt-after-precise-add",
                "android_group_participant_self",
                OBSERVED_BACKEND);

        org.assertj.core.api.Assertions.assertThat(result.addedGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly(groupJid);
    }

    @Test
    void incompleteSnapshotDoesNotMarkMissingMembershipsNotInGroup() {
        service.replaceVisibleGroups(
                10L, List.of(), false, 1783785600000L, "evt-incomplete", "android_groups_dirty",
                OBSERVED_BACKEND);

        Mockito.verify(membershipMapper, Mockito.never()).selectMissingMembershipIds(
                any(), any(), any(), any(Long.class));
        Mockito.verify(membershipMapper, Mockito.never()).markMembershipsNotInGroupByIds(
                any(), any(), any());
    }

    @Test
    void completeSnapshotMarksMissingMembershipsNotInGroup() {
        when(membershipMapper.selectMissingMembershipIds(
                10L, List.of(), List.of(3, 4), 1783785600000L))
                .thenReturn(List.of(42L, 41L, 42L));

        service.replaceVisibleGroups(
                10L, List.of(), true, 1783785600000L, "evt-complete", "android_groups_dirty",
                OBSERVED_BACKEND);

        Mockito.verify(membershipMapper).selectMissingMembershipIds(
                10L, List.of(), List.of(3, 4), 1783785600000L);
        ArgumentCaptor<AccountGroupMembership> rowCaptor =
                ArgumentCaptor.forClass(AccountGroupMembership.class);
        Mockito.verify(membershipMapper).markMembershipsNotInGroupByIds(
                org.mockito.ArgumentMatchers.eq(List.of(41L, 42L)),
                rowCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(List.of(3, 4)));
        org.assertj.core.api.Assertions.assertThat(rowCaptor.getValue())
                .extracting(
                        AccountGroupMembership::getMembershipStatus,
                        AccountGroupMembership::getStatusSource,
                        AccountGroupMembership::getStatusUpdatedAt,
                        AccountGroupMembership::getUpdatedAt)
                .containsExactly(5, "GROUP_SNAPSHOT", 1783785600000L, 1783785600000L);
    }

    private static AccountGroupsReportedEvent.Group group(String jid, String subject) {
        return new AccountGroupsReportedEvent.Group(
                jid, subject, null, null, null, false, false, null);
    }

    private void verifyPreviewPersist(InOrder inOrder, Long groupLinkId, String groupJid) {
        inOrder.verify(membershipMapper).upsertPreviewFromAccountSync(
                org.mockito.ArgumentMatchers.eq(groupLinkId),
                org.mockito.ArgumentMatchers.eq(groupJid),
                any(), any(), any(), any(), any(), any(Long.class), any(Long.class));
    }

    private void verifyHealthPersist(InOrder inOrder, Long groupLinkId) {
        inOrder.verify(healthMapper).upsertFromAccountGroupSync(
                org.mockito.ArgumentMatchers.argThat(row -> row.getGroupLinkId().equals(groupLinkId)));
    }

    private void verifyMembershipPersist(InOrder inOrder, String groupJid) {
        inOrder.verify(membershipMapper).upsertMembership(
                org.mockito.ArgumentMatchers.argThat(row -> row.getGroupJid().equals(groupJid)));
    }
}
