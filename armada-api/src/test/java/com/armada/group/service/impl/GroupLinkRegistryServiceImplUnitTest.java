package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.AccountObservedGroupWrite;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void setDataScope() {
        DataScopeContext.open(DataScope.self(1L));
    }

    @AfterEach
    void clearTenant() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void registerAccountObservedGroupRevivesArchivedGroupLinkMatchedByJid() {
        GroupLinkRegistryServiceImpl service =
                new GroupLinkRegistryServiceImpl(
                        groupLinkMapper, previewMapper,
                        currentSnapshotPersistence);
        when(groupLinkMapper.selectIdByGroupJidIncludingDeleted("120363001@g.us", 1L))
                .thenReturn(88L);

        Long result = service.registerAccountObservedGroup(
                "120363001@g.us", "测试群", ProtocolBackend.ANDROID, 1000L);

        assertThat(result).isEqualTo(88L);
        verify(groupLinkMapper).touchAccountObservedGroup(88L, 1L, "测试群", 2, 1000L);
        verify(groupLinkMapper, never()).insert(org.mockito.ArgumentMatchers.any(GroupLink.class));
    }

    @Test
    void registerAccountObservedGroupAtomicallyCreatesOrReusesDerivedLink() {
        GroupLinkRegistryServiceImpl service =
                new GroupLinkRegistryServiceImpl(
                        groupLinkMapper, previewMapper,
                        currentSnapshotPersistence);
        when(groupLinkMapper.selectIdByGroupJidIncludingDeleted("120363002@g.us", 1L))
                .thenReturn(null);
        org.mockito.Mockito.doReturn(1).when(groupLinkMapper).upsertAccountObservedGroup(
                org.mockito.ArgumentMatchers.any(GroupLink.class),
                org.mockito.ArgumentMatchers.eq("新群"));
        GroupLink resolved = new GroupLink();
        resolved.setId(99L);
        when(groupLinkMapper.selectAnyByUrlForUpdate("wa://group/120363002@g.us", 1L))
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
        verify(groupLinkMapper).selectAnyByUrlForUpdate("wa://group/120363002@g.us", 1L);
        verify(groupLinkMapper, never()).insert(org.mockito.ArgumentMatchers.any(GroupLink.class));
        verify(groupLinkMapper, never()).touchAccountObservedGroup(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void batchNewHandleFallsBackToJidWhenObservedNameIsBlank() {
        TenantContext.set(7L);
        when(groupLinkMapper.selectAccountObservedHandles(
                7L, 1L, List.of("120363003@g.us")))
                .thenReturn(
                        List.of(),
                        List.of(new com.armada.group.model.vo.AccountObservedGroupHandle(
                                "120363003@g.us", 103L, "wa://group/120363003@g.us")));
        Map<String, String> observed = new LinkedHashMap<>();
        observed.put("120363003@g.us", "  ");

        Map<String, Long> result = new GroupLinkRegistryServiceImpl(
                groupLinkMapper, previewMapper, currentSnapshotPersistence)
                .registerAccountObservedGroups(observed, ProtocolBackend.WEB, 3_000L);

        assertThat(result).containsEntry("120363003@g.us", 103L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountObservedGroupWrite>> rows = ArgumentCaptor.forClass(List.class);
        verify(groupLinkMapper).upsertAccountObservedGroups(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.insertGroupName()).isEqualTo("120363003@g.us");
            assertThat(row.observedGroupName()).isNull();
        });
    }

    @Test
    void knownMarketingMembershipIsWrittenToCurrentModel() {
        GroupLinkRegistryServiceImpl service = new GroupLinkRegistryServiceImpl(
                groupLinkMapper, previewMapper,
                currentSnapshotPersistence);
        GroupLink visible = new GroupLink();
        visible.setId(77L);
        visible.setOwnerUserId(1L);
        when(groupLinkMapper.selectActiveById(
                org.mockito.ArgumentMatchers.eq(77L),
                org.mockito.ArgumentMatchers.any(DataScope.class))).thenReturn(visible);

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
