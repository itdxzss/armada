package com.armada.promotion.pairing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingScene;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.tenant.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ControlPairingServiceImplTest {

    @Mock private PromotionPairingSessionMapper sessionMapper;
    @Mock private PromotionAccountProvisionService accountProvisionService;
    @Mock private IpProxyService ipProxyService;
    @Mock private ProxyResolver proxyResolver;
    @Mock private PairingLoginPort pairingLoginPort;
    @Mock private PromotionPairingTokenService tokenService;
    @Mock private PromotionPairingTransitionService transitionService;
    @Mock private PromotionPairingCompletionService completionService;
    @Mock private CountryService countryService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void authenticatedControlPairingAlwaysRequestsEightEights() {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            PromotionPairingSession session = invocation.getArgument(0);
            session.setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any(PromotionPairingSession.class));
        when(countryService.resolveIpRegionByPhonePrefix("919876543211")).thenReturn("IN");
        when(ipProxyService.allocatePairingEndpoint(7002L, "IN", true))
                .thenReturn(new IpProxyAllocation(1002L, null, "provider-a"));
        ProxyDescriptor proxy = new ProxyDescriptor(
                "socks5", "socks5://user:pass@proxy.internal:1080", "sticky-002", "IN");
        when(proxyResolver.resolve(null)).thenReturn(proxy);
        when(sessionMapper.attachProxy(
                7002L, 7L, 1002L, "sticky-002", "IN", "provider-a", 1L))
                .thenReturn(1);
        when(pairingLoginPort.requestCode(any(PairingCodeCommand.class)))
                .thenAnswer(invocation -> {
                    PairingCodeCommand command = invocation.getArgument(0);
                    return new PairingAccepted(
                            command.accountId(), "pairing-002", Instant.now().plusSeconds(90));
                });

        ControlPairingServiceImpl service = new ControlPairingServiceImpl(
                sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                () -> 1L);

        var result = service.create(new ControlPairingCreateCommand(
                "919876543211", 301L, "主设备异地导入", 81L));

        assertThat(result.sessionId()).isEqualTo(7002L);
        ArgumentCaptor<PairingCodeCommand> request = ArgumentCaptor.forClass(PairingCodeCommand.class);
        verify(pairingLoginPort).requestCode(request.capture());
        assertThat(request.getValue().customPairingCode()).isEqualTo("88888888");
        ArgumentCaptor<PromotionPairingSession> session =
                ArgumentCaptor.forClass(PromotionPairingSession.class);
        verify(transitionService).createControlSession(session.capture());
        assertThat(session.getValue().getPairingScene())
                .isEqualTo(PromotionPairingScene.CONTROL_ACCOUNT_IMPORT.code());
        assertThat(session.getValue().getAccountGroupId()).isEqualTo(301L);
        assertThat(session.getValue().getOwnerUserId()).isEqualTo(81L);
        verify(accountProvisionService).validateControlTarget(301L);
        verify(transitionService).markControlAccepted(
                7002L, 7L, "pairing-002", result.expiresAt(), 1L);
    }

    @Test
    void statusReturnsCodeOnlyFromTheCurrentTenantControlSession() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7002L);
        session.setTenantId(7L);
        session.setPairingScene(PromotionPairingScene.CONTROL_ACCOUNT_IMPORT.code());
        session.setStatus(PromotionPairingStatus.WAITING_CONFIRMATION.code());
        session.setPairingCode("88888888");
        session.setExpiresAt(92_001L);
        when(sessionMapper.selectByIdAndTenant(7002L, 7L)).thenReturn(session);
        ControlPairingServiceImpl service = new ControlPairingServiceImpl(
                sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                () -> 1L);

        var result = service.status(7002L, 7L);

        assertThat(result.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(result.pairingCode()).isEqualTo("88888888");
        verify(sessionMapper).selectByIdAndTenant(7002L, 7L);
    }
}
