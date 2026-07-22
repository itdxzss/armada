package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupMembershipChangedEvent;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 精确账号群关系事件 adapter 单测。 */
class AccountGroupMembershipChangedSinkAdapterTest {

    private final AccountGroupMembershipStatusService service =
            Mockito.mock(AccountGroupMembershipStatusService.class);
    private final AccountGroupMembershipChangedSinkAdapter adapter =
            new AccountGroupMembershipChangedSinkAdapter(service);

    @Test
    void selfRemoveMapsToGroupDomainEvent() {
        adapter.handleMembershipChanged(event("remove", "SELF", "120363001@g.us"));

        ArgumentCaptor<AccountGroupMembershipChangedEvent> captor =
                ArgumentCaptor.forClass(AccountGroupMembershipChangedEvent.class);
        verify(service).applyMembershipChanged(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().action()).isEqualTo("remove");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().occurredAt()).isEqualTo(2000L);
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
                selfParticipation, 2000L, "android_wgp2", "android-1");
    }
}
