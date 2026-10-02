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
    private final java.util.List<Runnable> queued = new java.util.ArrayList<>();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createReturnsBeforeProxyAllocationOrProtocolCall() {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            ((PromotionPairingSession) invocation.getArgument(0)).setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any());
        ControlPairingServiceImpl service = new ControlPairingServiceImpl(
                sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                queued::add, () -> 1L);
        var result = service.create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        assertThat(result.sessionId()).isEqualTo(7002L);
        assertThat(result.status()).isEqualTo("REQUESTING");
        org.mockito.Mockito.verifyNoInteractions(ipProxyService, pairingLoginPort);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void authenticatedControlPairingAlwaysRequestsEightEights(boolean acceptedWriteFails) {
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
                            command.accountId(), "pairing-002", Instant.ofEpochMilli(90_001L));
                });

        ControlPairingServiceImpl service = new ControlPairingServiceImpl(
                sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                queued::add, () -> 1L);

        if (acceptedWriteFails) {
            org.mockito.Mockito.doThrow(new IllegalStateException("accepted write raced with completion"))
                    .when(transitionService).markControlAccepted(7002L, 7L, "pairing-002", 90_001L, 1L);
        }
        var result = service.create(new ControlPairingCreateCommand(
                "919876543211", 301L, "主设备异地导入", 81L));

        assertThat(result.sessionId()).isEqualTo(7002L);
        queued.remove(0).run();
        org.mockito.Mockito.verifyNoInteractions(completionService);
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
                7002L, 7L, "pairing-002", 90_001L, 1L);
    }

    @Test
    void duplicateSubmissionResumesWithoutSchedulingAnotherProtocolRequest() {
        TenantContext.set(7L);
        when(sessionMapper.selectLatestControlByPhone("919876543211", 7L, 81L))
                .thenReturn(activeSession());
        var result = service().create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        assertThat(result.sessionId()).isEqualTo(7002L);
        assertThat(queued).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(tokenService, pairingLoginPort, transitionService);
    }

    @Test
    void concurrentInsertConflictRecoversCommittedSession() {
        TenantContext.set(7L);
        when(sessionMapper.selectLatestControlByPhone("919876543211", 7L, 81L))
                .thenReturn(null, activeSession());
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("active phone"))
                .when(transitionService).createControlSession(any());
        var result = service().create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        assertThat(result.sessionId()).isEqualTo(7002L);
        assertThat(queued).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(completionService, pairingLoginPort);
    }

    @Test
    void collisionWithAnotherOwnerDoesNotExposeOrCancelTheirSession() {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("active phone"))
                .when(transitionService).createControlSession(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().create(
                new ControlPairingCreateCommand("919876543211", 301L, null, 81L)))
                .hasMessage("该号码已有配对正在进行，请稍后再试");
        assertThat(queued).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(completionService, pairingLoginPort);
    }

    @Test
    void recoveryQueriesOnlyCurrentOwnerAndDoesNotCreateAnything() {
        TenantContext.set(7L);
        when(sessionMapper.selectLatestControlByPhone("919876543211", 7L, 81L))
                .thenReturn(activeSession());
        assertThat(service().recover("919876543211", 81L)).get()
                .extracting(com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO::sessionId)
                .isEqualTo(7002L);
        org.mockito.Mockito.verifyNoInteractions(transitionService, pairingLoginPort, ipProxyService);
    }

    @Test
    void cannotSilentlyChangeTheGroupOfAnActivePairing() {
        TenantContext.set(7L);
        when(sessionMapper.selectLatestControlByPhone("919876543211", 7L, 81L))
                .thenReturn(activeSession());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().create(
                new ControlPairingCreateCommand("919876543211", 999L, null, 81L)))
                .hasMessageContaining("其他账号分组");
        assertThat(queued).isEmpty();
    }

    @Test
    void rejectedExecutionEndsPersistedSessionRatherThanLeavingItBusy() {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            ((PromotionPairingSession) invocation.getArgument(0)).setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any());
        var service = new ControlPairingServiceImpl(
                sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                task -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                new ControlPairingCreateCommand("919876543211", 301L, null, 81L)))
                .hasMessageContaining("服务繁忙");
        verify(completionService).terminate(any(), org.mockito.ArgumentMatchers.eq(PromotionPairingStatus.FAILED),
                org.mockito.ArgumentMatchers.eq("PAIRING_REQUEST_FAILED"), any(), org.mockito.ArgumentMatchers.eq(1L));
    }

    @Test
    void backgroundFailureRebuildsTenantContextAndReleasesReservation() {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            ((PromotionPairingSession) invocation.getArgument(0)).setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any());
        when(countryService.resolveIpRegionByPhonePrefix("919876543211")).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            throw new IllegalStateException("allocation unavailable");
        });
        service().create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        TenantContext.set(99L);
        queued.remove(0).run();
        assertThat(TenantContext.get()).isEqualTo(99L);
        verify(completionService).terminate(any(), org.mockito.ArgumentMatchers.eq(PromotionPairingStatus.FAILED),
                org.mockito.ArgumentMatchers.eq("PAIRING_REQUEST_FAILED"), any(), org.mockito.ArgumentMatchers.eq(1L));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.armada.platform.protocol.exception.ProtocolErrorCode.class,
            names = {"TIMEOUT", "NETWORK"})
    void uncertainProtocolResultKeepsSessionForEventsInsteadOfPermittingDuplicatePairing(
            com.armada.platform.protocol.exception.ProtocolErrorCode errorCode) {
        TenantContext.set(7L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            ((PromotionPairingSession) invocation.getArgument(0)).setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any());
        when(countryService.resolveIpRegionByPhonePrefix("919876543211")).thenReturn("IN");
        when(ipProxyService.allocatePairingEndpoint(7002L, "IN", true))
                .thenReturn(new IpProxyAllocation(1002L, null, "provider-a"));
        when(proxyResolver.resolve(null)).thenReturn(new ProxyDescriptor(
                "socks5", "socks5://proxy.internal:1080", "sticky", "IN"));
        when(sessionMapper.attachProxy(7002L, 7L, 1002L, "sticky", "IN", "provider-a", 1L)).thenReturn(1);
        when(pairingLoginPort.requestCode(any())).thenThrow(
                new com.armada.platform.protocol.exception.ProtocolException(errorCode, "unconfirmed"));
        service().create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        TenantContext.clear();
        queued.remove(0).run();
        org.mockito.Mockito.verifyNoInteractions(completionService);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void expiredQueuedRequestNeverStartsAProtocolPairing() {
        TenantContext.set(7L);
        var now = new java.util.concurrent.atomic.AtomicLong(1L);
        when(tokenService.generate()).thenReturn(
                new PromotionPairingTokenService.GeneratedToken("unused", "a".repeat(64)));
        doAnswer(invocation -> {
            ((PromotionPairingSession) invocation.getArgument(0)).setId(7002L);
            return null;
        }).when(transitionService).createControlSession(any());
        var service = new ControlPairingServiceImpl(sessionMapper, accountProvisionService, ipProxyService,
                proxyResolver, pairingLoginPort, tokenService, transitionService, completionService,
                countryService, queued::add, now::get);
        service.create(new ControlPairingCreateCommand("919876543211", 301L, null, 81L));
        now.set(180_001L);
        queued.remove(0).run();
        verify(completionService).expireIfDue(7002L, 7L, 180_001L);
        org.mockito.Mockito.verifyNoInteractions(ipProxyService, pairingLoginPort);
    }

    @Test
    void keepsPollingDuringDeliveryGraceAndReturnsLateSuccess() {
        PromotionPairingSession session = activeSession();
        session.setStatus(PromotionPairingStatus.WAITING_CONFIRMATION.code());
        session.setPairingCode("88888888");
        session.setExpiresAt(100L);
        when(sessionMapper.selectByIdAndTenant(7002L, 7L)).thenReturn(session);
        var service = new ControlPairingServiceImpl(sessionMapper, accountProvisionService, ipProxyService,
                proxyResolver, pairingLoginPort, tokenService, transitionService, completionService,
                countryService, queued::add, () -> 101L);

        var pending = service.status(7002L, 7L);
        assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(pending.pairingCode()).isNull();
        org.mockito.Mockito.verifyNoInteractions(completionService);

        session.setStatus(PromotionPairingStatus.SUCCEEDED.code());
        session.setAccountId(9001L);
        var completed = service.status(7002L, 7L);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.accountId()).isEqualTo(9001L);
    }

    private ControlPairingServiceImpl service() {
        return new ControlPairingServiceImpl(sessionMapper, accountProvisionService, ipProxyService, proxyResolver,
                pairingLoginPort, tokenService, transitionService, completionService, countryService,
                queued::add, () -> 1L);
    }

    private PromotionPairingSession activeSession() {
        PromotionPairingSession row = new PromotionPairingSession();
        row.setId(7002L);
        row.setTenantId(7L);
        row.setOwnerUserId(81L);
        row.setAccountGroupId(301L);
        row.setPairingScene(PromotionPairingScene.CONTROL_ACCOUNT_IMPORT.code());
        row.setStatus(PromotionPairingStatus.REQUESTING.code());
        row.setExpiresAt(180_001L);
        return row;
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
                queued::add, () -> 1L);

        var result = service.status(7002L, 7L);

        assertThat(result.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(result.pairingCode()).isEqualTo("88888888");
        verify(sessionMapper).selectByIdAndTenant(7002L, 7L);
    }
}
