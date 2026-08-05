package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsappGroupBusinessDepartureServiceTest {

    @Mock private WhatsappGroupDepartedMemberService departedMemberService;
    @Mock private WhatsappGroupMemberCacheService memberCacheService;

    @Test
    void recordsBusinessInitiatedLeaveAsExplicitLeftFact() {
        WhatsappGroupBusinessDepartureService service = new WhatsappGroupBusinessDepartureService(
                departedMemberService, memberCacheService);

        service.recordConfirmedLeave(
                7L,
                "120363-TEST@G.US",
                "+52 18 1292 30974",
                1_000L,
                "group-pull-execution:501");

        ArgumentCaptor<List<WhatsappGroupDepartureFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(departedMemberService).saveLatest(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.tenantId()).isEqualTo(7L);
            assertThat(fact.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(fact.participantJid()).isEqualTo("5218129230974@s.whatsapp.net");
            assertThat(fact.phone()).isEqualTo("5218129230974");
            assertThat(fact.exitType()).isEqualTo("LEFT");
            assertThat(fact.exitedAt()).isEqualTo(1_000L);
            assertThat(fact.sourceType()).isEqualTo("BUSINESS_COMMAND");
            assertThat(fact.sourceEventId())
                    .isEqualTo("business-leave:group-pull-execution:501");
        });
        verify(memberCacheService).applyDepartures(captor.getValue());
    }
}
