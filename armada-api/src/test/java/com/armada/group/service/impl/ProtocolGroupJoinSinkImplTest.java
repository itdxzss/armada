package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtocolGroupJoinSinkImplTest {

    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private WhatsappGroupMemberJoinFactService joinFactService;
    @Mock private WhatsappGroupMemberCacheService memberCacheService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void handleJoinsValidatesCurrentBindingAndStoresCanonicalFact() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        TenantContext.set(99L);
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService);

        sink.handleJoins(event("android-10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupJoinFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(joinFactService).saveLatest(captor.capture());
        verify(memberCacheService).applyJoins(captor.getValue());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.tenantId()).isEqualTo(7L);
            assertThat(fact.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(fact.participantJid()).isEqualTo("15550000002@s.whatsapp.net");
            assertThat(fact.phone()).isEqualTo("15550000002");
            assertThat(fact.joinedAt()).isEqualTo(900L);
            assertThat(fact.observerAccountId()).isEqualTo(10L);
        });
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void handleJoinsRejectsStaleProtocolBinding() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-current", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService);

        sink.handleJoins(event("android-stale"));

        verify(joinFactService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void handleJoinsKeepsStableLidAndAddsTrustedPhoneAlias() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService);
        ProtocolGroupJoinEvent join = new ProtocolGroupJoinEvent(
                "event-lid", 7L, 10L, "android-10", "120363-test@g.us",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupJoinEvent.Participant(
                        "123456789012345:7@lid", "+52 181 292 30974", 900L, "source-lid")));

        sink.handleJoins(join);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupJoinFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(joinFactService).saveLatest(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.participantJid()).isEqualTo("123456789012345@lid");
            assertThat(fact.phone()).isEqualTo("5218129230974");
        });
    }

    private static ProtocolGroupJoinEvent event(String protocolAccountId) {
        return new ProtocolGroupJoinEvent(
                "event-1", 7L, 10L, protocolAccountId, " 120363-TEST@G.US ",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupJoinEvent.Participant(
                        "15550000002:17@s.whatsapp.net", "+1 555 000 0002", 900L, "source-add-1")));
    }
}
