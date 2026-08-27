package com.armada.promotion.pairing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionCapiEventServiceImplTest {

    @Mock
    private PromotionCapiEventOutboxMapper outboxMapper;

    @Test
    void initializeSnapshotsThreeOfficialEventsWithoutPlainPhone() {
        when(outboxMapper.batchInsert(anyList())).thenReturn(3);
        PromotionCapiEventServiceImpl service = new PromotionCapiEventServiceImpl(outboxMapper);

        service.initialize(
                session(),
                new PromotionChannelPairingContextRow(
                        7L, 501L, "印度投放", 81L, "IN", 1,
                        "Contact", "not-official", "Purchase"),
                new PromotionPairingAttribution(
                        "fb.1.1.browser", "fb.1.1.click", "https://go.example.com/c",
                        "203.0.113.10", "Armada-Test/1.0"),
                1_800_000_000_123L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PromotionCapiEventOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper).batchInsert(captor.capture());
        List<PromotionCapiEventOutbox> rows = captor.getValue();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(PromotionCapiEventOutbox::getEventName)
                .containsExactly("Contact", "InitiateCheckout", "Purchase");
        assertThat(rows).extracting(PromotionCapiEventOutbox::getStatus)
                .containsExactly(
                        PromotionCapiEventStatus.PENDING.code(),
                        PromotionCapiEventStatus.WAITING.code(),
                        PromotionCapiEventStatus.WAITING.code());
        assertThat(rows.get(0).getEventTime()).isEqualTo(1_800_000_000L);
        assertThat(rows.get(1).getEventTime()).isNull();
        assertThat(rows.get(0).getPhoneSha256())
                .hasSize(64)
                .doesNotContain("919876543210");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getTenantId()).isEqualTo(7L);
            assertThat(row.getOwnerUserId()).isEqualTo(81L);
            assertThat(row.getPairingSessionId()).isEqualTo(7001L);
            assertThat(row.getEventId()).startsWith("capi_").hasSize(37);
            assertThat(row.getFbc()).isEqualTo("fb.1.1.click");
        });
    }

    @Test
    void initializeDoesNothingForNonFacebookChannel() {
        PromotionCapiEventServiceImpl service = new PromotionCapiEventServiceImpl(outboxMapper);

        service.initialize(
                session(),
                new PromotionChannelPairingContextRow(
                        7L, 501L, "印度投放", 81L, "IN", 2,
                        null, null, null),
                new PromotionPairingAttribution(null, null, null, null, null),
                1_800_000_000_123L);

        verifyNoInteractions(outboxMapper);
    }

    private static PromotionPairingSession session() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7001L);
        session.setTenantId(7L);
        session.setOwnerUserId(81L);
        session.setPromotionChannelId(501L);
        session.setPhone("919876543210");
        return session;
    }
}
