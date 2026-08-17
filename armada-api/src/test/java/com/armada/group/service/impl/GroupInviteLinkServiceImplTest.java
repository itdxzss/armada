package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.GroupCurrentIdentity;
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

/** 群邀请码业务只写当前模型的边界测试。 */
class GroupInviteLinkServiceImplTest {

    private final GroupLinkRegistryService registry = mock(GroupLinkRegistryService.class);
    private final GroupLinkMapper groupLinkMapper = mock(GroupLinkMapper.class);
    private final GroupExecutionAccountSelector accountSelector = mock(GroupExecutionAccountSelector.class);
    private final GroupInvitePort invitePort = mock(GroupInvitePort.class);
    private final GroupCurrentInvitePersistence currentPersistence = mock(GroupCurrentInvitePersistence.class);
    private final GroupInviteLinkServiceImpl service = new GroupInviteLinkServiceImpl(
            registry, groupLinkMapper, accountSelector, invitePort, currentPersistence);

    @Test
    void observationRegistersHandleAndWritesCurrentInviteAndHealth() {
        when(registry.registerAccountObservedGroup(
                "120363group@g.us", null, ProtocolBackend.ANDROID, 2_000L)).thenReturn(51L);

        service.applyCurrentInvite(new GroupInviteLinkObservation(
                "evt-1", null, "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, "wgp2_notification", 2_000L));

        verify(currentPersistence).apply(
                51L, "120363group@g.us", "NewInviteCode_2026", 2_000L);
        ArgumentCaptor<GroupLinkHealth> health = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentPersistence).applyHealth(eq("120363group@g.us"), health.capture());
        assertThat(health.getValue().getHealthStatus()).isEqualTo(1);
        assertThat(health.getValue().getLastCheckAt()).isEqualTo(2_000L);
    }

    @Test
    void resolveAndRefreshUseCurrentIdentity() {
        when(groupLinkMapper.selectCurrentIdentity(51L)).thenReturn(
                new GroupCurrentIdentity(51L, "120363group@g.us", "CurrentCode"));

        assertThat(service.resolveCurrentInviteCode(51L, "FrozenCode")).isEqualTo("CurrentCode");
        assertThat(service.refreshCurrentInviteCode(51L, "120363group@g.us", "FrozenCode"))
                .contains("CurrentCode");
        verifyNoInteractions(accountSelector, invitePort);
    }

    @Test
    void activeRefreshWritesReplacementToCurrentModel() {
        when(groupLinkMapper.selectCurrentIdentity(51L)).thenReturn(
                new GroupCurrentIdentity(51L, "120363group@g.us", "FrozenCode"));
        GroupExecutionAccount admin = new GroupExecutionAccount(
                901L, "web", "acc-901", "8613800000901", true);
        when(accountSelector.findCandidates(51L)).thenReturn(List.of(admin));
        when(invitePort.getInvite(admin.protocolRef(), "120363group@g.us")).thenReturn(
                new GroupInviteResult("120363group@g.us", "ReplacementCode",
                        "https://chat.whatsapp.com/ReplacementCode"));

        assertThat(service.refreshCurrentInviteCode(51L, "120363group@g.us", "FrozenCode"))
                .contains("ReplacementCode");

        verify(currentPersistence).apply(
                eq(51L), eq("120363group@g.us"), eq("ReplacementCode"), anyLong());
        verifyNoInteractions(registry);
    }

    @Test
    void bindingAlwaysBindsHandleAndReusesObservedInvite() {
        when(groupLinkMapper.selectCurrentIdentity(51L)).thenReturn(
                new GroupCurrentIdentity(51L, null, "ObservedBeforeJoin"));

        service.bindGroupJid(51L, "120363joined@g.us", 4_000L);

        verify(currentPersistence).bindGroup(51L, "120363joined@g.us", 4_000L);
        verify(currentPersistence).apply(
                51L, "120363joined@g.us", "ObservedBeforeJoin", 4_000L);
    }

    @Test
    void publicPreviewWritesOnlyCurrentInviteModel() {
        service.applyPublicPreview(51L, 8L, new GroupInvitePageMetadata(
                "PublicCode", "公开群名", "https://cdn.example/public.jpg"), 2_000L);

        ArgumentCaptor<GroupLinkPreview> preview = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(currentPersistence).applyPublicPreview(preview.capture(), eq(8L));
        assertThat(preview.getValue().getGroupLinkId()).isEqualTo(51L);
        assertThat(preview.getValue().getInviteCode()).isEqualTo("PublicCode");
        assertThat(preview.getValue().getWaSubject()).isEqualTo("公开群名");
    }
}
