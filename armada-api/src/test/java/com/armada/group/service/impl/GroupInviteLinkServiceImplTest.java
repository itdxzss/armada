package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroupInviteLinkServiceImplTest {

    private final GroupLinkRegistryService registry = mock(GroupLinkRegistryService.class);
    private final GroupLinkPreviewMapper previewMapper = mock(GroupLinkPreviewMapper.class);
    private final GroupLinkHealthMapper healthMapper = mock(GroupLinkHealthMapper.class);
    private final GroupExecutionAccountSelector accountSelector =
            mock(GroupExecutionAccountSelector.class);
    private final GroupInvitePort invitePort = mock(GroupInvitePort.class);
    private final GroupCurrentInvitePersistence currentInvitePersistence =
            mock(GroupCurrentInvitePersistence.class);
    private final GroupInviteLinkServiceImpl service =
            new GroupInviteLinkServiceImpl(
                    registry, previewMapper, healthMapper, accountSelector,
                    invitePort, currentInvitePersistence);

    @Test
    void applyRegistersObservedGroupAndStoresCurrentInviteCode() {
        when(registry.registerAccountObservedGroup(
                "120363group@g.us", null, ProtocolBackend.ANDROID, 1786341600000L))
                .thenReturn(51L);
        when(healthMapper.insertAvailableFromInviteObservationIfAbsent(
                org.mockito.ArgumentMatchers.any(GroupLinkHealth.class))).thenReturn(1);

        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "evt-1", null, "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, "wgp2_notification", 1786341600000L));

        ArgumentCaptor<GroupLinkPreview> captor =
                ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsertInviteLinkChange(captor.capture());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(captor.getValue().getGroupJid()).isEqualTo("120363group@g.us");
        assertThat(captor.getValue().getInviteCode()).isEqualTo("NewInviteCode_2026");
        assertThat(captor.getValue().getInviteCodeObservedAt()).isEqualTo(1786341600000L);
        verify(currentInvitePersistence).apply(
                "120363group@g.us", "NewInviteCode_2026", 1786341600000L);
        ArgumentCaptor<GroupLinkHealth> healthCaptor =
                ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                org.mockito.ArgumentMatchers.eq("120363group@g.us"),
                healthCaptor.capture());
        assertThat(healthCaptor.getValue().getHealthStatus()).isEqualTo(1);
        assertThat(healthCaptor.getValue().getBanned()).isFalse();
        assertThat(healthCaptor.getValue().getLastCheckAt()).isEqualTo(1786341600000L);
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

    @Test
    void refreshUsesAnAlreadyObservedReplacementWithoutQueryingWhatsapp() {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setInviteCode("PassiveReplacement_2026");
        when(previewMapper.selectByGroupLinkId(51L)).thenReturn(preview);

        assertThat(service.refreshCurrentInviteCode(
                51L, "120363group@g.us", "FrozenCode"))
                .contains("PassiveReplacement_2026");
        verifyNoInteractions(accountSelector, invitePort);
    }

    @Test
    void refreshQueriesAnOnlineGroupAdminAndStoresTheReplacementOnTheOriginalGroup() {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupJid("120363group@g.us");
        preview.setInviteCode("FrozenCode");
        when(previewMapper.selectByGroupLinkId(51L)).thenReturn(preview);
        GroupExecutionAccount admin = new GroupExecutionAccount(
                901L, "web", "acc-901", "8613800000901", true);
        when(accountSelector.findCandidates(51L)).thenReturn(List.of(admin));
        when(invitePort.getInvite(admin.protocolRef(), "120363group@g.us"))
                .thenReturn(new GroupInviteResult(
                        "120363group@g.us", "ActiveReplacement_2026",
                        "https://chat.whatsapp.com/ActiveReplacement_2026"));

        assertThat(service.refreshCurrentInviteCode(
                51L, "120363group@g.us", "FrozenCode"))
                .contains("ActiveReplacement_2026");

        ArgumentCaptor<GroupLinkPreview> captor =
                ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsertInviteLinkChange(captor.capture());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(captor.getValue().getGroupJid()).isEqualTo("120363group@g.us");
        assertThat(captor.getValue().getInviteCode()).isEqualTo("ActiveReplacement_2026");
        verifyNoInteractions(registry);
    }

    @Test
    void publicObservationWriterUsesTheSuppliedGroupIdentity() {
        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "manual-observation-1", 51L, "120363group@g.us",
                "ObservedReplacement_2026", ProtocolBackend.WEB,
                "MANUAL_REFRESH", 3_000L));

        ArgumentCaptor<GroupLinkPreview> captor =
                ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsertInviteLinkChange(captor.capture());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(captor.getValue().getInviteCode()).isEqualTo("ObservedReplacement_2026");
        assertThat(captor.getValue().getInviteCodeObservedAt()).isEqualTo(3_000L);
        verifyNoInteractions(registry);
    }

    @Test
    void rejectedLegacyHealthObservationDoesNotOverwriteGroupProfile() {
        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "stale-observation-1", 51L, "120363group@g.us",
                "StaleReplacement_2026", ProtocolBackend.WEB,
                "MANUAL_REFRESH", 2_000L));

        org.mockito.Mockito.verify(currentInvitePersistence, org.mockito.Mockito.never())
                .applyHealth(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(GroupLinkHealth.class));
    }

    @Test
    void bindingGroupJidAlsoBindsThePreviouslyObservedInvite() {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setInviteCode("ObservedBeforeJoin_2026");
        when(previewMapper.selectByGroupLinkId(51L)).thenReturn(preview);

        service.bindGroupJid(51L, "120363joined@g.us", 4_000L);

        verify(currentInvitePersistence).apply(
                "120363joined@g.us", "ObservedBeforeJoin_2026", 4_000L);
    }

    @Test
    void publicPreviewWritesLegacyAndNewInviteModels() {
        GroupInvitePageMetadata metadata = new GroupInvitePageMetadata(
                "PublicCode_2026", "公开群名", "https://cdn.example/public.jpg");

        service.applyPublicPreview(51L, 8L, metadata, 2_000L);

        ArgumentCaptor<GroupLinkPreview> captor = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsertInvitePageMetadata(captor.capture());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(captor.getValue().getInviteCode()).isEqualTo("PublicCode_2026");
        assertThat(captor.getValue().getWaSubject()).isEqualTo("公开群名");
        verify(currentInvitePersistence).applyPublicPreview(captor.getValue(), 8L);
    }
}
