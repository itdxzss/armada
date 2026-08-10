package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkChangedEvent;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroupInviteLinkServiceImplTest {

    private final GroupLinkRegistryService registry = mock(GroupLinkRegistryService.class);
    private final GroupLinkPreviewMapper previewMapper = mock(GroupLinkPreviewMapper.class);
    private final GroupInviteLinkServiceImpl service =
            new GroupInviteLinkServiceImpl(registry, previewMapper);

    @Test
    void applyRegistersObservedGroupAndStoresCurrentInviteCode() {
        when(registry.registerAccountObservedGroup(
                "120363group@g.us", null, ProtocolBackend.ANDROID, 1786341600000L))
                .thenReturn(51L);

        service.apply(new GroupInviteLinkChangedEvent(
                "evt-1", "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, 1786341600000L));

        ArgumentCaptor<GroupLinkPreview> captor =
                ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsertInviteLinkChange(captor.capture());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(captor.getValue().getGroupJid()).isEqualTo("120363group@g.us");
        assertThat(captor.getValue().getInviteCode()).isEqualTo("NewInviteCode_2026");
        assertThat(captor.getValue().getInviteCodeObservedAt()).isEqualTo(1786341600000L);
    }

    @Test
    void resolveCurrentInviteCodeUsesPreviewAndFallsBackToFrozenCode() {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setInviteCode("NewInviteCode_2026");
        when(previewMapper.selectByGroupLinkId(51L)).thenReturn(preview);

        assertThat(service.resolveCurrentInviteCode(51L, "FrozenCode"))
                .isEqualTo("NewInviteCode_2026");
        assertThat(service.resolveCurrentInviteCode(null, "FrozenCode"))
                .isEqualTo("FrozenCode");
    }
}
