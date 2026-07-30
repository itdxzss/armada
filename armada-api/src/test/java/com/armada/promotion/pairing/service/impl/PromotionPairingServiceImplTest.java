package com.armada.promotion.pairing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.command.PromotionPairingCreateCommand;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionPairingServiceImplTest {

    @Mock
    private PromotionChannelService channelService;
    @Mock
    private PromotionPairingSessionMapper sessionMapper;
    @Mock
    private PromotionAccountProvisionService accountProvisionService;
    @Mock
    private IpProxyService ipProxyService;
    @Mock
    private PairingLoginPort pairingLoginPort;
    @Mock
    private PromotionPairingCompletionService completionService;
    @Mock
    private PromotionPairingTransitionService transitionService;

    @Test
    void createStoresOnlyTokenHashAndUsesDedicatedPairingProxyReservation() {
        when(channelService.resolvePairingContext("bewbmr9k", "go.example.com"))
                .thenReturn(facebookContext());
        when(accountProvisionService.existsActiveByPhoneGlobally("919876543210")).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<PromotionPairingSession>getArgument(0).setId(7001L);
            return null;
        }).when(transitionService).createSession(any(), any(), any(), anyLong());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP, "proxy.internal", 8080,
                new ProxyCredentials("user", "pwd_session-sticky001"), "IN");
        when(ipProxyService.allocatePairingEndpoint(7001L, "IN", true))
                .thenReturn(new IpProxyAllocation(1001L, endpoint, "provider-a"));
        when(sessionMapper.attachProxy(
                eq(7001L), eq(7L), eq(1001L), eq("sticky001"), eq("IN"), eq("provider-a"), anyLong()))
                .thenReturn(1);
        when(pairingLoginPort.requestCode(any(PairingCodeCommand.class)))
                .thenAnswer(invocation -> new PairingAccepted(
                        invocation.<PairingCodeCommand>getArgument(0).accountId(),
                        "pairing-001",
                        Instant.now().plusSeconds(180)));
        PromotionPairingServiceImpl service = new PromotionPairingServiceImpl(
                channelService, sessionMapper, accountProvisionService, ipProxyService,
                new ProxyResolver(), pairingLoginPort, new PromotionPairingTokenService(),
                transitionService, completionService);

        var result = service.create(createCommand());

        assertThat(result.sessionToken()).isNotBlank();
        ArgumentCaptor<PromotionPairingSession> sessionCaptor =
                ArgumentCaptor.forClass(PromotionPairingSession.class);
        verify(transitionService).createSession(
                sessionCaptor.capture(), eq(facebookContext()), any(), anyLong());
        assertThat(sessionCaptor.getValue().getSessionTokenHash())
                .isNotEqualTo(result.sessionToken())
                .hasSize(64);
        assertThat(sessionCaptor.getValue().getTenantId()).isEqualTo(7L);
        assertThat(sessionCaptor.getValue().getChannelName()).isEqualTo("印度投放");

        ArgumentCaptor<PairingCodeCommand> commandCaptor = ArgumentCaptor.forClass(PairingCodeCommand.class);
        verify(pairingLoginPort).requestCode(commandCaptor.capture());
        assertThat(commandCaptor.getValue().accountId())
                .startsWith("acc_pair_")
                .isNotEqualTo("acc_919876543210");
        assertThat(commandCaptor.getValue().phone()).isEqualTo("919876543210");
        assertThat(commandCaptor.getValue().proxy().sessionId()).isEqualTo("sticky001");
        verify(transitionService).markAccepted(
                eq(7001L), eq(7L), eq("pairing-001"), anyLong(), anyLong());
    }

    @Test
    void statusReturnsSucceededWhenCompletionWinsExpirationRace() {
        PromotionPairingTokenService tokenService = new PromotionPairingTokenService();
        PromotionPairingSession expiredSnapshot = new PromotionPairingSession();
        expiredSnapshot.setId(7001L);
        expiredSnapshot.setTenantId(7L);
        expiredSnapshot.setStatus(PromotionPairingStatus.WAITING_CONFIRMATION.code());
        expiredSnapshot.setExpiresAt(1L);

        PromotionPairingSession completedSnapshot = new PromotionPairingSession();
        completedSnapshot.setId(7001L);
        completedSnapshot.setTenantId(7L);
        completedSnapshot.setStatus(PromotionPairingStatus.SUCCEEDED.code());
        completedSnapshot.setExpiresAt(1L);
        completedSnapshot.setAccountId(901L);

        String tokenHash = tokenService.hash("session-token-once");
        when(sessionMapper.selectByTokenHash(tokenHash))
                .thenReturn(expiredSnapshot, completedSnapshot);
        when(completionService.expireIfDue(eq(7001L), eq(7L), anyLong()))
                .thenReturn(false);
        PromotionPairingServiceImpl service = new PromotionPairingServiceImpl(
                channelService, sessionMapper, accountProvisionService, ipProxyService,
                new ProxyResolver(), pairingLoginPort, tokenService, transitionService, completionService);

        var result = service.status("session-token-once");

        assertThat(result.status()).isEqualTo(PromotionPairingStatus.SUCCEEDED.name());
        assertThat(result.accountId()).isEqualTo(901L);
    }

    @Test
    void createReleasesAllocatedProxyWhenProxyResolutionFails() {
        when(channelService.resolvePairingContext("bewbmr9k", "go.example.com"))
                .thenReturn(facebookContext());
        when(accountProvisionService.existsActiveByPhoneGlobally("919876543210")).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<PromotionPairingSession>getArgument(0).setId(7001L);
            return null;
        }).when(transitionService).createSession(any(), any(), any(), anyLong());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP, "proxy.internal", 8080,
                new ProxyCredentials("user", "pwd_session-sticky001"), "IN");
        when(ipProxyService.allocatePairingEndpoint(7001L, "IN", true))
                .thenReturn(new IpProxyAllocation(1001L, endpoint, "provider-a"));
        ProxyResolver failingResolver = mock(ProxyResolver.class);
        when(failingResolver.resolve(endpoint)).thenThrow(new IllegalStateException("bad proxy"));
        PromotionPairingServiceImpl service = new PromotionPairingServiceImpl(
                channelService, sessionMapper, accountProvisionService, ipProxyService,
                failingResolver, pairingLoginPort, new PromotionPairingTokenService(),
                transitionService, completionService);

        assertThatThrownBy(() -> service.create(createCommand()))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);

        ArgumentCaptor<PromotionPairingSession> sessionCaptor =
                ArgumentCaptor.forClass(PromotionPairingSession.class);
        verify(completionService).terminate(
                sessionCaptor.capture(), eq(PromotionPairingStatus.FAILED),
                eq("PAIRING_REQUEST_FAILED"), eq("配对请求失败，请重试"), anyLong());
        assertThat(sessionCaptor.getValue().getProxyId()).isEqualTo(1001L);
    }

    @Test
    void createRejectsPhoneAlreadyOwnedByAnyTenantBeforeAllocatingProxy() {
        when(channelService.resolvePairingContext("bewbmr9k", "go.example.com"))
                .thenReturn(facebookContext());
        when(accountProvisionService.existsActiveByPhoneGlobally("919876543210")).thenReturn(true);
        PromotionPairingServiceImpl service = new PromotionPairingServiceImpl(
                channelService, sessionMapper, accountProvisionService, ipProxyService,
                new ProxyResolver(), pairingLoginPort, new PromotionPairingTokenService(),
                transitionService, completionService);

        assertThatThrownBy(() -> service.create(createCommand()))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("配对暂不可用");

        verifyNoInteractions(ipProxyService, pairingLoginPort);
    }

    @Test
    void createDiscardsInvalidOptionalAttributionWithoutBlockingPairing() {
        when(channelService.resolvePairingContext("bewbmr9k", "go.example.com"))
                .thenReturn(facebookContext());
        when(accountProvisionService.existsActiveByPhoneGlobally("919876543210")).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<PromotionPairingSession>getArgument(0).setId(7001L);
            return null;
        }).when(transitionService).createSession(any(), any(), any(), anyLong());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP, "proxy.internal", 8080,
                new ProxyCredentials("user", "pwd_session-sticky001"), "IN");
        when(ipProxyService.allocatePairingEndpoint(7001L, "IN", true))
                .thenReturn(new IpProxyAllocation(1001L, endpoint, "provider-a"));
        when(sessionMapper.attachProxy(
                eq(7001L), eq(7L), eq(1001L), eq("sticky001"), eq("IN"), eq("provider-a"), anyLong()))
                .thenReturn(1);
        when(pairingLoginPort.requestCode(any(PairingCodeCommand.class)))
                .thenAnswer(invocation -> new PairingAccepted(
                        invocation.<PairingCodeCommand>getArgument(0).accountId(),
                        "pairing-001", Instant.now().plusSeconds(180)));
        PromotionPairingServiceImpl service = new PromotionPairingServiceImpl(
                channelService, sessionMapper, accountProvisionService, ipProxyService,
                new ProxyResolver(), pairingLoginPort, new PromotionPairingTokenService(),
                transitionService, completionService);
        PromotionPairingCreateCommand malformed = new PromotionPairingCreateCommand(
                "bewbmr9k", "go.example.com", "919876543210",
                "bad cookie value", "bad/click", "https://evil.example/path?secret=value",
                "999.999.999.999", "bad\u0000agent");

        service.create(malformed);

        ArgumentCaptor<PromotionPairingAttribution> attributionCaptor =
                ArgumentCaptor.forClass(PromotionPairingAttribution.class);
        verify(transitionService).createSession(
                any(), eq(facebookContext()), attributionCaptor.capture(), anyLong());
        assertThat(attributionCaptor.getValue()).isEqualTo(
                new PromotionPairingAttribution(null, null, null, null, null));
    }

    private static PromotionChannelPairingContextRow facebookContext() {
        return new PromotionChannelPairingContextRow(
                7L, 501L, "印度投放", 81L, "IN", 1,
                "Lead", "InitiateCheckout", "CompleteRegistration");
    }

    private static PromotionPairingCreateCommand createCommand() {
        return new PromotionPairingCreateCommand(
                "bewbmr9k", "go.example.com", "919876543210",
                "fb.1.1.browser", "fb.1.1.click",
                "https://go.example.com/bewbmr9k", "203.0.113.10", "Armada-Test/1.0");
    }
}
