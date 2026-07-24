package com.armada.promotion.pairing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.PromotionAccountProvisionCommand;
import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEvent;
import com.armada.platform.protocol.model.result.PairingCredentialExport;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.resource.service.IpProxyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionPairingCompletionServiceTest {

    @Mock
    private PromotionPairingSessionMapper sessionMapper;
    @Mock
    private PromotionAccountProvisionService accountProvisionService;
    @Mock
    private IpProxyService ipProxyService;

    @Test
    void completedEventCreatesFormalAccountAndTransfersTemporaryProxy() {
        PromotionPairingSession session = waitingSession();
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(session);
        when(sessionMapper.claimFinalizing(7001L, 7L, 1_800_000_000_000L)).thenReturn(1);
        when(accountProvisionService.provision(any(PromotionAccountProvisionCommand.class))).thenReturn(901L);
        when(sessionMapper.markSucceeded(7001L, 7L, 901L, 1_800_000_000_000L)).thenReturn(1);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        Long accountId = service.complete(
                7001L,
                7L,
                new ProtocolPairingEvent(
                        "evt-2", ProtocolPairingEvent.EVENT_COMPLETED, "acc_919876543210", "7001",
                        1_800_000_000_000L, "worker-1", null, null,
                        "919876543210", "919876543210@s.whatsapp.net",
                        "http://protocol-worker-1:3000", null, "BUSINESS_VERIFIED"),
                new PairingCredentialExport(
                        "acc_919876543210",
                        "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}"));

        assertThat(accountId).isEqualTo(901L);
        verify(ipProxyService).confirmPairingAllocation(7001L, 901L, 1001L);
        ArgumentCaptor<PromotionAccountProvisionCommand> commandCaptor =
                ArgumentCaptor.forClass(PromotionAccountProvisionCommand.class);
        verify(accountProvisionService).provision(commandCaptor.capture());
        assertThat(commandCaptor.getValue().promotionChannelId()).isEqualTo(501L);
        assertThat(commandCaptor.getValue().proxySessionId()).isEqualTo("sticky001");
        assertThat(commandCaptor.getValue().accountType()).isEqualTo(2);
    }

    @Test
    void completedEventAfterExpiryEndsSessionWithoutCreatingAccount() {
        PromotionPairingSession session = waitingSession();
        session.setExpiresAt(1_799_999_999_999L);
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(session);
        when(sessionMapper.markTerminal(
                7001L, 7L, PromotionPairingStatus.EXPIRED.code(),
                "PAIRING_EXPIRED", "配对码已失效，请重试", 1_800_000_000_000L)).thenReturn(1);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        Long accountId = service.complete(
                7001L,
                7L,
                new ProtocolPairingEvent(
                        "evt-late", ProtocolPairingEvent.EVENT_COMPLETED, "acc_919876543210", "7001",
                        1_800_000_000_000L, "worker-1", null, null,
                        "919876543210", "919876543210@s.whatsapp.net", null, null, "PERSONAL"),
                new PairingCredentialExport(
                        "acc_919876543210",
                        "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}"));

        assertThat(accountId).isNull();
        verify(sessionMapper).markTerminal(
                7001L, 7L, PromotionPairingStatus.EXPIRED.code(),
                "PAIRING_EXPIRED", "配对码已失效，请重试", 1_800_000_000_000L);
        verifyNoInteractions(accountProvisionService);
    }

    @Test
    void completedEventCannotCreatePhoneAlreadyOwnedByAnotherTenant() {
        PromotionPairingSession session = waitingSession();
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(session);
        when(accountProvisionService.existsActiveByPhoneGlobally("919876543210")).thenReturn(true);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        assertThatThrownBy(() -> service.complete(
                7001L,
                7L,
                new ProtocolPairingEvent(
                        "evt-conflict", ProtocolPairingEvent.EVENT_COMPLETED, "acc_919876543210", "7001",
                        1_800_000_000_000L, "worker-1", null, null,
                        "919876543210", "919876543210@s.whatsapp.net", null, null, "PERSONAL"),
                new PairingCredentialExport(
                        "acc_919876543210",
                        "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}")))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("配对暂不可用");

        verify(accountProvisionService).existsActiveByPhoneGlobally("919876543210");
        verifyNoInteractions(ipProxyService);
    }

    @Test
    void completedEventRejectsUnknownAccountTypeInsteadOfPersistingAsPersonal() {
        PromotionPairingSession session = waitingSession();
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(session);
        when(sessionMapper.claimFinalizing(7001L, 7L, 1_800_000_000_000L)).thenReturn(1);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        assertThatThrownBy(() -> service.complete(
                7001L,
                7L,
                new ProtocolPairingEvent(
                        "evt-unknown", ProtocolPairingEvent.EVENT_COMPLETED,
                        "acc_919876543210", "7001", 1_800_000_000_000L,
                        "worker-1", null, null, "919876543210",
                        "919876543210@s.whatsapp.net", null, null, "UNKNOWN"),
                new PairingCredentialExport(
                        "acc_919876543210",
                        "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}")))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("未明确识别账号类型");

        verifyNoInteractions(ipProxyService);
        verify(accountProvisionService).existsActiveByPhoneGlobally("919876543210");
        verifyNoMoreInteractions(accountProvisionService);
    }

    @Test
    void expiryRechecksLockedRowAndKeepsSessionWhoseDeadlineWasExtended() {
        PromotionPairingSession current = waitingSession();
        current.setExpiresAt(1_800_000_180_000L);
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(current);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        boolean expired = service.expireIfDue(7001L, 7L, 1_800_000_000_000L);

        assertThat(expired).isFalse();
        verify(sessionMapper).selectByIdForUpdate(7001L, 7L);
        verifyNoMoreInteractions(sessionMapper);
        verifyNoInteractions(ipProxyService);
    }

    @Test
    void expiryUsesCurrentLockedProxyInsteadOfScannerSnapshot() {
        PromotionPairingSession current = waitingSession();
        current.setExpiresAt(1_799_999_999_999L);
        current.setProxyId(2002L);
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(current);
        when(sessionMapper.markTerminal(
                7001L, 7L, PromotionPairingStatus.EXPIRED.code(),
                "PAIRING_EXPIRED", "配对码已失效，请重试", 1_800_000_000_000L)).thenReturn(1);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        boolean expired = service.expireIfDue(7001L, 7L, 1_800_000_000_000L);

        assertThat(expired).isTrue();
        verify(ipProxyService).releasePairingAllocation(7001L, 2002L);
    }

    @Test
    void expiryFallsBackToTemporarySessionBindingWhenProxyWasNotPersisted() {
        PromotionPairingSession current = waitingSession();
        current.setExpiresAt(1_799_999_999_999L);
        current.setProxyId(null);
        when(sessionMapper.selectByIdForUpdate(7001L, 7L)).thenReturn(current);
        when(sessionMapper.markTerminal(
                7001L, 7L, PromotionPairingStatus.EXPIRED.code(),
                "PAIRING_EXPIRED", "配对码已失效，请重试", 1_800_000_000_000L)).thenReturn(1);
        PromotionPairingCompletionService service = new PromotionPairingCompletionService(
                sessionMapper, accountProvisionService, ipProxyService);

        boolean expired = service.expireIfDue(7001L, 7L, 1_800_000_000_000L);

        assertThat(expired).isTrue();
        verify(ipProxyService).releasePairingAllocationBySession(7001L);
    }

    private static PromotionPairingSession waitingSession() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7001L);
        session.setTenantId(7L);
        session.setPromotionChannelId(501L);
        session.setChannelName("印度投放");
        session.setOwnerUserId(81L);
        session.setPhone("919876543210");
        session.setProtocolAccountId("acc_919876543210");
        session.setStatus(PromotionPairingStatus.WAITING_CONFIRMATION.code());
        session.setProxyId(1001L);
        session.setProxySessionId("sticky001");
        session.setProxyRegion("IN");
        session.setProxySource("provider-a");
        session.setExpiresAt(1_800_000_180_000L);
        return session;
    }
}
