package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupMembershipChangedEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 精确账号群关系事件 adapter 单测。 */
class AccountGroupMembershipChangedSinkAdapterTest {

    private final AccountGroupMembershipStatusService service =
            Mockito.mock(AccountGroupMembershipStatusService.class);
    private final MarketingNewGroupImmediateSendService marketing =
            Mockito.mock(MarketingNewGroupImmediateSendService.class);
    private final AccountGroupMembershipChangedSinkAdapter adapter =
            new AccountGroupMembershipChangedSinkAdapter(service, marketing);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void selfRemoveMapsToGroupDomainEvent() {
        adapter.handleMembershipChanged(event("remove", "SELF", "120363001@g.us"));

        ArgumentCaptor<AccountGroupMembershipChangedEvent> captor =
                ArgumentCaptor.forClass(AccountGroupMembershipChangedEvent.class);
        verify(service).applyMembershipChanged(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().action()).isEqualTo("remove");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().occurredAt()).isEqualTo(2000L);
        verifyNoInteractions(marketing);
    }

    @Test
    void selfAddEnqueuesDelayOnlyForNewMembershipAndUsesDetectionTime() {
        Mockito.when(service.applyMembershipChanged(Mockito.any()))
                .thenReturn(true, false);
        TenantContext.set(99L);
        Mockito.doAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            return null;
        }).when(marketing).enqueueDelayedNewGroups(
                Mockito.anyLong(), Mockito.anyList(), Mockito.anyLong());
        long detectedAfter = System.currentTimeMillis();

        adapter.handleMembershipChanged(event("add", "SELF", "120363001@g.us"));
        adapter.handleMembershipChanged(event("add", "SELF", "120363001@g.us"));

        verify(marketing).enqueueDelayedNewGroups(
                Mockito.eq(100L),
                Mockito.eq(List.of(new MarketingNewGroupDTO(null, "120363001@g.us", null))),
                Mockito.longThat(detectedAt -> detectedAt >= detectedAfter));
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void staleBindingOrRepeatedSelfAddDoesNotRegisterNewGroup() {
        adapter.handleMembershipChanged(event("add", "SELF", "120363001@g.us"));

        verifyNoInteractions(marketing);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void rejectsOtherParticipantUnknownActionAndNonGroupJid() {
        assertThatThrownBy(() -> adapter.handleMembershipChanged(event("remove", "OTHER", "120363001@g.us")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.handleMembershipChanged(event("promote", "SELF", "120363001@g.us")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.handleMembershipChanged(event("remove", "SELF", "86138000@s.whatsapp.net")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }

    private static ProtocolAccountGroupMembershipChangedEvent event(
            String action, String selfParticipation, String groupJid) {
        return new ProtocolAccountGroupMembershipChangedEvent(
                "evt-1", 7L, 100L, "acc-1", groupJid, action,
                selfParticipation, "wgp2-event-1", 2000L, "android_wgp2", "android-1");
    }
}
