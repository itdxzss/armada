package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureEvent;
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
class ProtocolGroupDepartureSinkImplTest {

    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private WhatsappGroupDepartedMemberService departedMemberService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void handleDeparturesValidatesCurrentTenantAccountBindingAndRestoresContext() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenAnswer(ignored -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            return Optional.of(account);
        });
        TenantContext.set(99L);
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountLookupService, departedMemberService);

        sink.handleDepartures(event("android-10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupDepartureFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(departedMemberService).saveLatest(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.tenantId()).isEqualTo(7L);
            assertThat(fact.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(fact.participantJid()).isEqualTo("15550000002@s.whatsapp.net");
            assertThat(fact.phone()).isEqualTo("15550000002");
            assertThat(fact.exitType()).isEqualTo("LEFT");
        });
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void handleDeparturesRejectsStaleProtocolBinding() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-current", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountLookupService, departedMemberService);

        sink.handleDepartures(event("android-stale"));

        verify(departedMemberService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void handleDeparturesDerivesPhoneFromPnButNotFromLid() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountLookupService, departedMemberService);
        ProtocolGroupDepartureEvent departure = new ProtocolGroupDepartureEvent(
                "event-2", 7L, 10L, "android-10", "120363-test@g.us",
                "HISTORY_SYNC", 1_000L,
                List.of(
                        new ProtocolGroupDepartureEvent.Participant(
                                "15550000003:9@s.whatsapp.net", null,
                                "LEFT", 900L, "source-2"),
                        new ProtocolGroupDepartureEvent.Participant(
                                "123456789012345@lid", null,
                                "REMOVED", 901L, "source-3")));

        sink.handleDepartures(departure);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupDepartureFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(departedMemberService).saveLatest(captor.capture());
        assertThat(captor.getValue()).satisfiesExactly(
                fact -> {
                    assertThat(fact.participantJid()).isEqualTo("15550000003@s.whatsapp.net");
                    assertThat(fact.phone()).isEqualTo("15550000003");
                },
                fact -> {
                    assertThat(fact.participantJid()).isEqualTo("123456789012345@lid");
                    assertThat(fact.phone()).isNull();
                });
    }

    private static ProtocolGroupDepartureEvent event(String protocolAccountId) {
        return new ProtocolGroupDepartureEvent(
                "event-1", 7L, 10L, protocolAccountId, " 120363-TEST@G.US ",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupDepartureEvent.Participant(
                        "15550000002:17@s.whatsapp.net", "+1 555 000 0002", "LEFT", 900L, "source-1")));
    }
}
