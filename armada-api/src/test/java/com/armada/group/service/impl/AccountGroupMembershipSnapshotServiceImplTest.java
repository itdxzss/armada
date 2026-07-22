package com.armada.group.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.GroupLinkRegistryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AccountGroupMembershipSnapshotServiceImplTest {

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
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(99L);

        service.replaceVisibleGroups(
                10L,
                List.of(new AccountGroupsReportedEvent.Group(
                        groupJid, null, null, null, null, null, null, null)),
                true,
                1783785600000L,
                "evt-light-groups",
                "wa_groups_dirty");

        Mockito.verify(registryService)
                .registerAccountObservedGroup(
                        org.mockito.ArgumentMatchers.eq(groupJid),
                        org.mockito.ArgumentMatchers.isNull(),
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
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(11L);
        when(registryService.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq("120363new@g.us"),
                org.mockito.ArgumentMatchers.eq("新群"),
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
                "wa_groups_dirty");

        org.assertj.core.api.Assertions.assertThat(result.currentGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly("120363old@g.us", "120363new@g.us");
        org.assertj.core.api.Assertions.assertThat(result.addedGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly("120363new@g.us");
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
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(13L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(group(groupJid, "旧快照群")),
                false,
                1_000L,
                "evt-stale",
                "android_groups_dirty");

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
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(14L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(group(groupJid, "精确新增群")),
                true,
                2_000L,
                "evt-after-precise-add",
                "android_group_participant_self");

        org.assertj.core.api.Assertions.assertThat(result.addedGroups())
                .extracting(AccountGroupMembershipSnapshot::groupJid)
                .containsExactly(groupJid);
    }

    @Test
    void incompleteSnapshotDoesNotMarkMissingMembershipsNotInGroup() {
        service.replaceVisibleGroups(
                10L, List.of(), false, 1783785600000L, "evt-incomplete", "android_groups_dirty");

        Mockito.verify(membershipMapper, Mockito.never()).markMissingMembershipsNotInGroup(
                any(), any(), any(Integer.class), any(), any(), any(Long.class), any(Long.class));
    }

    @Test
    void completeSnapshotMarksMissingMembershipsNotInGroup() {
        service.replaceVisibleGroups(
                10L, List.of(), true, 1783785600000L, "evt-complete", "android_groups_dirty");

        Mockito.verify(membershipMapper).markMissingMembershipsNotInGroup(
                10L, List.of(), 5, List.of(3, 4), "GROUP_SNAPSHOT", 1783785600000L,
                1783785600000L);
    }

    private static AccountGroupsReportedEvent.Group group(String jid, String subject) {
        return new AccountGroupsReportedEvent.Group(
                jid, subject, null, null, null, false, false, null);
    }
}
