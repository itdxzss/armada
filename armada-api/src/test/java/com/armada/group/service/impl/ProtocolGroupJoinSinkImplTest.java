package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.ControlledAccountGroupTransition;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
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
    @Mock private GroupParticipantObservationService observationService;
    @Mock private MarketingNewGroupImmediateSendService marketing;

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
                accountLookupService, joinFactService, memberCacheService, observationService, marketing);

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
        verifyNoInteractions(marketing);
    }

    @Test
    void handleJoinsRejectsStaleProtocolBinding() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-current", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService, observationService, marketing);

        sink.handleJoins(event("android-stale"));

        verify(joinFactService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(observationService, marketing);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void handleJoinsKeepsStableLidAndAddsTrustedPhoneAlias() {
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(account));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService, observationService, marketing);
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
        verify(observationService).reconcileControlledJoins(
                7L, "120363-test@g.us", List.of(
                        "123456789012345@lid", "5218129230974@s.whatsapp.net"), 900L, "event-lid");
    }

    @Test
    void newlyJoinedControlledParticipantWaitsFromDetectionButReplayDoesNotEnqueueAgain() {
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(
                new ProtocolAccountRef(10L, ProtocolBackend.ANDROID, "android-10", "15550000001")));
        when(observationService.reconcileControlledJoins(
                7L, "120363-test@g.us", List.of("15550000002@s.whatsapp.net"),
                900L, "event-1"))
                .thenReturn(List.of(new ControlledAccountGroupTransition(77L, "120363-test@g.us")))
                .thenReturn(List.of());
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService, observationService, marketing);
        long receivedAfter = System.currentTimeMillis();

        sink.handleJoins(event("android-10"));
        sink.handleJoins(event("android-10"));

        var order = inOrder(observationService, joinFactService, memberCacheService, marketing);
        order.verify(observationService).reconcileControlledJoins(
                7L, "120363-test@g.us", List.of("15550000002@s.whatsapp.net"), 900L, "event-1");
        order.verify(joinFactService).saveLatest(org.mockito.ArgumentMatchers.anyList());
        order.verify(memberCacheService).applyJoins(org.mockito.ArgumentMatchers.anyList());
        verify(marketing).enqueueDelayedNewGroups(
                org.mockito.ArgumentMatchers.eq(77L),
                org.mockito.ArgumentMatchers.eq(List.of(new MarketingNewGroupDTO(
                        null, "120363-test@g.us", null))),
                org.mockito.ArgumentMatchers.longThat(value -> value >= receivedAfter));
    }

    @Test
    void missingEnvelopeTimeUsesEachParticipantsActualJoinTime() {
        when(accountLookupService.findActiveProtocolRef(10L)).thenReturn(Optional.of(
                new ProtocolAccountRef(10L, ProtocolBackend.ANDROID, "android-10", "15550000001")));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountLookupService, joinFactService, memberCacheService, observationService, marketing);

        sink.handleJoins(new ProtocolGroupJoinEvent(
                "mixed-join-times", 7L, 10L, "android-10", "120363-test@g.us",
                "WGP2_NOTIFICATION", null,
                List.of(new ProtocolGroupJoinEvent.Participant(
                                "15550000002@s.whatsapp.net", null, 900L, "first"),
                        new ProtocolGroupJoinEvent.Participant(
                                "15550000003@s.whatsapp.net", null, 1_100L, "second"))));

        verify(observationService).reconcileControlledJoins(
                7L, "120363-test@g.us", List.of("15550000002@s.whatsapp.net"), 900L, "mixed-join-times");
        verify(observationService).reconcileControlledJoins(
                7L, "120363-test@g.us", List.of("15550000003@s.whatsapp.net"), 1_100L, "mixed-join-times");
        verifyNoInteractions(marketing);
    }

    private static ProtocolGroupJoinEvent event(String protocolAccountId) {
        return new ProtocolGroupJoinEvent(
                "event-1", 7L, 10L, protocolAccountId, " 120363-TEST@G.US ",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupJoinEvent.Participant(
                        "15550000002:17@s.whatsapp.net", "+1 555 000 0002", 900L, "source-add-1")));
    }
}
