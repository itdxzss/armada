package com.armada.group.service.impl;

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
    void resolvesStableHandleAndWritesOnlyCreatorCompatibility() {
        when(registry.registerAccountObservedGroup(
                org.mockito.ArgumentMatchers.eq("120363001@g.us"),
                org.mockito.ArgumentMatchers.eq("群一"),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(20L);
        GroupLink handle = new GroupLink();
        handle.setId(20L);
        handle.setLinkUrl("wa://group/120363001@g.us");
        handle.setGroupName("群一");
        when(groupLinkMapper.selectActiveById(20L)).thenReturn(handle);

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
    }

    private AccountGroupMembershipSnapshotServiceImpl service() {
        return new AccountGroupMembershipSnapshotServiceImpl(
                groupLinkMapper, previewMapper, registry, classification);
    }
}
