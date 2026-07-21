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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AccountGroupMembershipSnapshotServiceImplTest {

    private final AccountGroupMembershipMapper membershipMapper =
            Mockito.mock(AccountGroupMembershipMapper.class);
    private final GroupLinkMapper groupLinkMapper = Mockito.mock(GroupLinkMapper.class);
    private final GroupLinkHealthMapper healthMapper = Mockito.mock(GroupLinkHealthMapper.class);
    private final AccountGroupMembershipSnapshotServiceImpl service =
            new AccountGroupMembershipSnapshotServiceImpl(membershipMapper, groupLinkMapper, healthMapper);

    @Test
    void replaceVisibleGroups_usesGroupJidAsNewLinkNameWhenSubjectIsMissing() {
        String groupJid = "120363000000000001@g.us";
        when(membershipMapper.selectActiveGroupLinkIdByGroupJid(groupJid)).thenReturn(null);
        when(groupLinkMapper.selectAnyByUrl("wa://group/" + groupJid)).thenReturn(null);
        doAnswer(invocation -> {
            GroupLink row = invocation.getArgument(0);
            row.setId(99L);
            return 1;
        }).when(groupLinkMapper).insert(any(GroupLink.class));

        service.replaceVisibleGroups(
                10L,
                List.of(new AccountGroupsReportedEvent.Group(
                        groupJid, null, null, null, null, null, null, null)),
                1783785600000L,
                "evt-light-groups",
                "wa_groups_dirty");

        ArgumentCaptor<GroupLink> captor = ArgumentCaptor.forClass(GroupLink.class);
        Mockito.verify(groupLinkMapper).insert(captor.capture());
        assertEquals(groupJid, captor.getValue().getGroupName());
    }

    @Test
    void replaceVisibleGroups_returnsOnlyGroupsMissingBeforeRefreshAsAdded() {
        when(membershipMapper.selectActiveGroupJids(10L))
                .thenReturn(List.of("120363old@g.us"));
        when(membershipMapper.selectActiveGroupLinkIdByGroupJid("120363old@g.us"))
                .thenReturn(11L);
        when(membershipMapper.selectActiveGroupLinkIdByGroupJid("120363new@g.us"))
                .thenReturn(12L);

        AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
                10L,
                List.of(
                        group("120363old@g.us", "旧群"),
                        group("120363new@g.us", "新群"),
                        group("120363new@g.us", "重复新群")),
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

    private static AccountGroupsReportedEvent.Group group(String jid, String subject) {
        return new AccountGroupsReportedEvent.Group(
                jid, subject, null, null, null, false, false, null);
    }
}
