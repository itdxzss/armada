package com.armada.group.service.impl;

import com.armada.platform.country.service.CountryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 账号群快照仅保留句柄与创建者兼容写的单测。 */
@ExtendWith(MockitoExtension.class)
class AccountGroupMembershipSnapshotServiceImplTest {

    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private GroupLinkPreviewMapper previewMapper;
    @Mock private GroupLinkRegistryService registry;
    @Mock private GroupClassificationService classification;

    @Test
    void initialHistoricalGroupListWithOnlyLidFallsBackToLegacyGroupJid() {
        String jid = "916375552817-1517537054@g.us";
        when(registry.registerAccountObservedGroups(org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.ANDROID), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of(jid, 20L));
        GroupLink handle = new GroupLink();
        handle.setId(20L);
        when(groupLinkMapper.selectActiveByIds(List.of(20L))).thenReturn(List.of(handle));
        service().replaceVisibleGroups(10L, List.of(new AccountGroupsReportedEvent.Group(
                jid, "历史群", 20, "47970506555552@lid", null, false, false, null)),
                true, 2_000L, "evt", "online_full_metadata", ProtocolBackend.ANDROID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getOwnerPhone()).isEqualTo("916375552817");
            assertThat(row.getCreatorPhoneSource()).isEqualTo(1);
        });
    }

    @Test
    void resolvesStableHandleAndWritesOnlyCreatorCompatibility() {
        when(registry.registerAccountObservedGroups(
                org.mockito.ArgumentMatchers.eq(Map.of("120363001@g.us", "群一")),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("120363001@g.us", 20L));
        GroupLink handle = new GroupLink();
        handle.setId(20L);
        handle.setLinkUrl("wa://group/120363001@g.us");
        handle.setGroupName("群一");
        when(groupLinkMapper.selectActiveByIds(List.of(20L))).thenReturn(List.of(handle));

        var result = service().replaceVisibleGroups(
                10L,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363001@g.us", "群一", 20, null,
                        "15550000001", true, false, null)),
                true, 2_000L, "evt", "test", ProtocolBackend.WEB);

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.groupLinkId()).isEqualTo(20L);
            assertThat(row.groupJid()).isEqualTo("120363001@g.us");
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> compatibility = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(compatibility.capture());
        assertThat(compatibility.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getGroupLinkId()).isEqualTo(20L);
            assertThat(row.getOwnerPhone()).isEqualTo("15550000001");
            assertThat(row.getLastPreviewAt()).isEqualTo(2_000L);
        });
        verify(registry).registerAccountObservedGroups(
                org.mockito.ArgumentMatchers.eq(Map.of("120363001@g.us", "群一")),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unresolvedLidDoesNotWriteAnEmptyCreator() {
        when(registry.registerAccountObservedGroups(
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(ProtocolBackend.ANDROID),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(Map.of("120363001@g.us", 20L));
        GroupLink handle = new GroupLink();
        handle.setId(20L);
        when(groupLinkMapper.selectActiveByIds(List.of(20L))).thenReturn(List.of(handle));
        service().replaceVisibleGroups(10L, List.of(new AccountGroupsReportedEvent.Group(
                "120363001@g.us", "群一", 20, "47970506555552@lid", null, true, false, null)),
                true, 2_000L, "evt", "test", ProtocolBackend.ANDROID);
        org.mockito.Mockito.verifyNoInteractions(previewMapper);
    }

    private AccountGroupMembershipSnapshotServiceImpl service() {
        return new AccountGroupMembershipSnapshotServiceImpl(
                groupLinkMapper,
                new GroupCreatorCompatibilityWriter(previewMapper, org.mockito.Mockito.mock(CountryService.class)), registry, classification);
    }
}
