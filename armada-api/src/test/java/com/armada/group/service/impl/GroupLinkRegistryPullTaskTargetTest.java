package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 拉群任务群入口登记测试。 */
@ExtendWith(MockitoExtension.class)
class GroupLinkRegistryPullTaskTargetTest {

    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Test
    void insertsNewLinkAsPullTaskTargetAndReturnsGeneratedId() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(null);
        when(groupLinkMapper.insert(any(GroupLink.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, GroupLink.class).setId(77L);
            return 1;
        });

        Map<String, Long> ids = service().registerPullTaskTargets(List.of(LINK_A), 1000L);

        assertThat(ids).containsExactly(org.assertj.core.data.MapEntry.entry(LINK_A, 77L));
        ArgumentCaptor<GroupLink> captor = ArgumentCaptor.forClass(GroupLink.class);
        verify(groupLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
        assertThat(captor.getValue().getMembershipState())
                .isEqualTo(GroupMembershipState.TARGET.code());
    }

    @Test
    void reusesActiveLinkWithoutInsertingOrReviving() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(activeLink(55L));

        assertThat(service().registerPullTaskTargets(List.of(LINK_A), 1000L))
                .containsEntry(LINK_A, 55L);

        verify(groupLinkMapper, never()).insert(any(GroupLink.class));
        verify(groupLinkMapper, never()).reviveAsStandaloneTarget(anyLong(), anyLong());
    }

    @Test
    void currentObservedInviteReusesTheOriginalGroupEntry() {
        when(previewMapper.selectActiveGroupLinkIdByInviteCode(
                "BBBBBBBBBBBBBBBBBBBBBB")).thenReturn(55L);

        assertThat(service().registerPullTaskTargets(List.of(LINK_B), 1000L))
                .containsEntry(LINK_B, 55L);

        verify(groupLinkMapper, never()).selectAnyByUrl(LINK_B);
        verify(groupLinkMapper, never()).insert(any(GroupLink.class));
    }

    @Test
    void revivesSoftDeletedLinkAndKeepsItsId() {
        GroupLink deleted = activeLink(66L);
        deleted.setDeletedAt(900L);
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(deleted);

        assertThat(service().registerPullTaskTargets(List.of(LINK_A), 1000L))
                .containsEntry(LINK_A, 66L);

        // 软删行仍占 link_url 唯一键，必须复活原行而不是插新行。
        verify(groupLinkMapper).reviveAsStandaloneTarget(66L, 1000L);
        verify(groupLinkMapper, never()).insert(any(GroupLink.class));
    }

    @Test
    void registersEachDistinctLinkOnlyOnce() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(activeLink(55L));

        Map<String, Long> ids = service().registerPullTaskTargets(
                List.of(LINK_A, LINK_A, LINK_A), 1000L);

        assertThat(ids).hasSize(1);
        verify(groupLinkMapper).selectAnyByUrl(LINK_A);
    }

    @Test
    void skipsUnparseableLinkWithoutFailingTheBatch() {
        when(groupLinkMapper.selectAnyByUrl(LINK_B)).thenReturn(activeLink(88L));

        Map<String, Long> ids = service().registerPullTaskTargets(
                List.of("不是链接", LINK_B), 1000L);

        assertThat(ids).containsOnlyKeys(LINK_B);
    }

    @Test
    void joinTaskPathStillRegistersWithJoinTaskOrigin() {
        when(groupLinkMapper.selectAnyByUrl(LINK_A)).thenReturn(null);

        service().registerJoinTaskTargets(List.of(LINK_A));

        ArgumentCaptor<GroupLink> captor = ArgumentCaptor.forClass(GroupLink.class);
        verify(groupLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrigin()).isEqualTo(GroupLinkOrigin.JOIN_TASK.code());
    }

    private GroupLinkRegistryServiceImpl service() {
        return new GroupLinkRegistryServiceImpl(
                groupLinkMapper, membershipMapper, previewMapper);
    }

    private static GroupLink activeLink(long id) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setLinkUrl(LINK_A);
        return link;
    }
}
