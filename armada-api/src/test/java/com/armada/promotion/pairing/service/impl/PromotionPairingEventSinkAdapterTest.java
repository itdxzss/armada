package com.armada.promotion.pairing.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEvent;
import com.armada.platform.protocol.model.result.PairingCredentialExport;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingScene;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionPairingEventSinkAdapterTest {

    @Mock
    private PromotionPairingSessionMapper sessionMapper;
    @Mock
    private PairingLoginPort pairingLoginPort;
    @Mock
    private PromotionPairingCompletionService completionService;

    @Test
    void completedEventAcceptsBaileysMultiDevicePhoneSuffix() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7001L);
        session.setTenantId(7L);
        session.setCreatedAt(1_000L);
        session.setPhone("919876543210");
        session.setProtocolAccountId("acc_919876543210");
        when(sessionMapper.selectActiveByProtocolAccountId("acc_919876543210")).thenReturn(session);
        PairingCredentialExport credential = new PairingCredentialExport(
                "acc_919876543210", "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}");
        when(pairingLoginPort.exportCredential("acc_919876543210")).thenReturn(credential);
        ProtocolPairingEvent event = new ProtocolPairingEvent(
                "evt-2", ProtocolPairingEvent.EVENT_COMPLETED, "acc_919876543210", null,
                1_800_000_000_000L, "worker-1", null, null,
                "919876543210:12", "919876543210:12@s.whatsapp.net",
                "http://protocol-worker-1:3000", null, "PERSONAL");
        PromotionPairingEventSinkAdapter adapter = new PromotionPairingEventSinkAdapter(
                sessionMapper, pairingLoginPort, completionService);

        adapter.handle(event);

        verify(completionService).complete(7001L, 7L, event, credential);
    }

    @Test
    void eventFromUnknownOneTimeAccountDoesNotMutateAnySession() {
        when(sessionMapper.selectActiveByProtocolAccountId("acc_pair_old_attempt")).thenReturn(null);
        ProtocolPairingEvent staleEvent = new ProtocolPairingEvent(
                "evt-old", ProtocolPairingEvent.EVENT_FAILED, "acc_pair_old_attempt", null,
                2_001L, "worker-1", null, null, null, null, null, "user_timeout", null);
        PromotionPairingEventSinkAdapter adapter = new PromotionPairingEventSinkAdapter(
                sessionMapper, pairingLoginPort, completionService);

        adapter.handle(staleEvent);

        verifyNoInteractions(pairingLoginPort, completionService);
    }

    @Test
    void controlPairingFailsClosedWhenProtocolDoesNotReturnEightEights() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7002L);
        session.setTenantId(7L);
        session.setPairingScene(PromotionPairingScene.CONTROL_ACCOUNT_IMPORT.code());
        session.setProtocolAccountId("acc_pair_control");
        when(sessionMapper.selectActiveByProtocolAccountId("acc_pair_control")).thenReturn(session);
        ProtocolPairingEvent event = new ProtocolPairingEvent(
                "evt-code", ProtocolPairingEvent.EVENT_CODE_GENERATED, "acc_pair_control", null,
                2_001L, "worker-1", "ABCD1234", 92_001L,
                null, null, null, null, null);
        PromotionPairingEventSinkAdapter adapter = new PromotionPairingEventSinkAdapter(
                sessionMapper, pairingLoginPort, completionService);

        adapter.handle(event);

        verify(completionService).terminate(
                session, PromotionPairingStatus.FAILED, "CONTROL_PAIRING_CODE_MISMATCH",
                "协议层未返回指定认证码，请重试", 2_001L);
        verifyNoInteractions(pairingLoginPort);
    }
}
