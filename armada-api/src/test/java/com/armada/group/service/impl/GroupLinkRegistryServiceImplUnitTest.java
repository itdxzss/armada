package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupLinkRegistryServiceImplUnitTest {

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    @Test
    void registerAccountObservedGroupRevivesArchivedGroupLinkMatchedByJid() {
        GroupLinkRegistryServiceImpl service =
                new GroupLinkRegistryServiceImpl(
                        groupLinkMapper, previewMapper,
                        currentSnapshotPersistence);
        when(groupLinkMapper.selectIdByGroupJidIncludingDeleted("120363001@g.us"))
                .thenReturn(88L);

        Long result = service.registerAccountObservedGroup(
                "120363001@g.us", "测试群", ProtocolBackend.ANDROID, 1000L);

        assertThat(result).isEqualTo(88L);
        verify(groupLinkMapper).touchAccountObservedGroup(88L, "测试群", 2, 1000L);
        verify(groupLinkMapper, never()).insert(org.mockito.ArgumentMatchers.any(GroupLink.class));
    }

    @Test
    void registerAccountObservedGroupAtomicallyCreatesOrReusesDerivedLink() {
        GroupLinkRegistryServiceImpl service =
                new GroupLinkRegistryServiceImpl(
                        groupLinkMapper, previewMapper,
                        currentSnapshotPersistence);
        when(groupLinkMapper.selectIdByGroupJidIncludingDeleted("120363002@g.us"))
                .thenReturn(null);
        org.mockito.Mockito.doReturn(1).when(groupLinkMapper).upsertAccountObservedGroup(
                org.mockito.ArgumentMatchers.any(GroupLink.class),
                org.mockito.ArgumentMatchers.eq("新群"));
        GroupLink resolved = new GroupLink();
        resolved.setId(99L);
        when(groupLinkMapper.selectAnyByUrlForUpdate("wa://group/120363002@g.us"))
                .thenReturn(resolved);

        Long result = service.registerAccountObservedGroup(
                "120363002@g.us", "新群", ProtocolBackend.ANDROID, 2000L);

        assertThat(result).isEqualTo(99L);
        ArgumentCaptor<GroupLink> rowCaptor = ArgumentCaptor.forClass(GroupLink.class);
        verify(groupLinkMapper).upsertAccountObservedGroup(rowCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("新群"));
        assertThat(rowCaptor.getValue().getLinkUrl()).isEqualTo("wa://group/120363002@g.us");
        assertThat(rowCaptor.getValue().getGroupName()).isEqualTo("新群");
        assertThat(rowCaptor.getValue().getOrigin()).isEqualTo(GroupLinkOrigin.ACCOUNT_SYNC.code());
        assertThat(rowCaptor.getValue().getMembershipState()).isEqualTo(GroupMembershipState.JOINED.code());
        assertThat(rowCaptor.getValue().getSyncProtocolMask()).isEqualTo(2);
        verify(groupLinkMapper).selectAnyByUrlForUpdate("wa://group/120363002@g.us");
        verify(groupLinkMapper, never()).insert(org.mockito.ArgumentMatchers.any(GroupLink.class));
        verify(groupLinkMapper, never()).touchAccountObservedGroup(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void knownMarketingMembershipIsWrittenToCurrentModel() {
        GroupLinkRegistryServiceImpl service = new GroupLinkRegistryServiceImpl(
                groupLinkMapper, previewMapper,
                currentSnapshotPersistence);

        service.registerKnownMembership(
                77L, "120363003@g.us", 301L, true, 3_000L);

        verify(currentSnapshotPersistence).applySelfMembershipChanged(
                301L, "120363003@g.us",
                AccountGroupMembershipStatus.IN_GROUP,
                3_000L, "registry:77:301:3000", "GROUP_PULL_MARKETING");
        verify(currentSnapshotPersistence).applyControlledParticipantObservation(
                301L, "120363003@g.us", true, true, 3_000L,
                "registry:77:301:3000", "GROUP_PULL_MARKETING");
    }
}
