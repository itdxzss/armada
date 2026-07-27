package com.armada.promotion.pairing.service.impl;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStage;
import com.armada.promotion.pairing.service.PromotionCapiEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionPairingTransitionServiceTest {

    @Mock
    private PromotionPairingSessionMapper sessionMapper;
    @Mock
    private PromotionCapiEventService capiEventService;

    @Test
    void createSessionPersistsSessionBeforeInitializingOutbox() {
        PromotionPairingSession session = new PromotionPairingSession();
        PromotionChannelPairingContextRow context = new PromotionChannelPairingContextRow(
                7L, 501L, "印度投放", 81L, "IN", 1,
                "Lead", "InitiateCheckout", "CompleteRegistration");
        PromotionPairingAttribution attribution =
                new PromotionPairingAttribution(null, null, null, null, null);
        when(sessionMapper.insert(session)).thenAnswer(invocation -> {
            session.setId(7001L);
            return 1;
        });
        PromotionPairingTransitionService service =
                new PromotionPairingTransitionService(sessionMapper, capiEventService);

        service.createSession(session, context, attribution, 1_800_000_000_000L);

        InOrder ordered = inOrder(sessionMapper, capiEventService);
        ordered.verify(sessionMapper).insert(session);
        ordered.verify(capiEventService).initialize(
                session, context, attribution, 1_800_000_000_000L);
    }

    @Test
    void markAcceptedActivatesLoginRequestAfterStateTransition() {
        when(sessionMapper.markAccepted(
                7001L, 7L, "pairing-1", 1_800_000_180_000L, 1_800_000_000_000L))
                .thenReturn(1);
        PromotionPairingTransitionService service =
                new PromotionPairingTransitionService(sessionMapper, capiEventService);

        service.markAccepted(
                7001L, 7L, "pairing-1", 1_800_000_180_000L, 1_800_000_000_000L);

        InOrder ordered = inOrder(sessionMapper, capiEventService);
        ordered.verify(sessionMapper).markAccepted(
                7001L, 7L, "pairing-1", 1_800_000_180_000L, 1_800_000_000_000L);
        ordered.verify(capiEventService).activate(
                7001L, PromotionCapiEventStage.LOGIN_REQUEST, 1_800_000_000_000L);
    }
}
