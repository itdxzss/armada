package com.armada.account.recovery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.tenant.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyFailedRecoveryCoordinatorTest {

    @Mock
    private IpProxyService ipProxyService;

    @Mock
    private AccountOnlineCommandService onlineCommandService;

    @Mock
    private ProtocolCommandOutboxService outboxService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void recover_marksExactFailedProxyUnavailableBeforeStartingIndependentReonlineTransaction() {
        when(ipProxyService.markFailedProxyUnavailable(100L, 7L, 2_000L)).thenReturn(true);
        ProxyFailedRecoveryCoordinator coordinator = coordinator();

        coordinator.recover(1L, 100L, "oa_failed_1", 7L, 2_000L);

        InOrder inOrder = inOrder(ipProxyService, onlineCommandService);
        inOrder.verify(ipProxyService).markFailedProxyUnavailable(100L, 7L, 2_000L);
        inOrder.verify(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L, 2_000L);
    }

    @Test
    void recover_markUnavailableFailurePreventsReallocationWithoutEscapingToKafkaConsumer() {
        doThrow(new IllegalStateException("release failed"))
                .when(ipProxyService).markFailedProxyUnavailable(100L, 7L, 2_000L);

        assertThatCode(() -> coordinator().recover(1L, 100L, "oa_failed_1", 7L, 2_000L))
                .doesNotThrowAnyException();

        verifyNoInteractions(onlineCommandService);
    }

    @Test
    void recover_legacyEventResolvesExactAttemptBeforeQuarantiningProxy() {
        when(ipProxyService.markFailedProxyUnavailable(100L, 7L, 2_000L)).thenReturn(true);
        when(outboxService.findOnlineAttemptProxyId(100L, "oa_failed_1"))
                .thenReturn(Optional.of(7L));

        coordinator().recover(1L, 100L, "oa_failed_1", null, 2_000L);

        InOrder order = inOrder(outboxService, ipProxyService, onlineCommandService);
        order.verify(outboxService).findOnlineAttemptProxyId(100L, "oa_failed_1");
        order.verify(ipProxyService).markFailedProxyUnavailable(100L, 7L, 2_000L);
        order.verify(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L, 2_000L);
    }

    @Test
    void recover_unresolvedAttemptDoesNotReleaseOrReallocateAnyProxy() {
        when(outboxService.findOnlineAttemptProxyId(100L, "oa_missing"))
                .thenReturn(Optional.empty());

        coordinator().recover(1L, 100L, "oa_missing", null, 2_000L);

        verifyNoInteractions(ipProxyService, onlineCommandService);
    }

    @Test
    void recover_resolutionFailureKeepsProxyQuarantinableAndRestoresTenant() {
        when(outboxService.findOnlineAttemptProxyId(100L, "oa_failed_1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        TenantContext.set(99L);

        assertThatCode(() -> coordinator().recover(1L, 100L, "oa_failed_1", null, 2_000L))
                .doesNotThrowAnyException();

        verifyNoInteractions(ipProxyService, onlineCommandService);
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void recover_reonlineFailureDoesNotEscapeAndRestoresPreviousTenant() {
        when(ipProxyService.markFailedProxyUnavailable(100L, 7L, 2_000L)).thenReturn(true);
        doThrow(new IllegalStateException("no idle proxy"))
                .when(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L, 2_000L);
        TenantContext.set(99L);

        assertThatCode(() -> coordinator().recover(1L, 100L, "oa_failed_1", 7L, 2_000L))
                .doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }

    private ProxyFailedRecoveryCoordinator coordinator() {
        return new ProxyFailedRecoveryCoordinator(ipProxyService, onlineCommandService, outboxService);
    }

    @Test
    void recover_doesNotReallocateWhenQuarantineReturnsFalse() {
        coordinator().recover(1L, 100L, "oa_failed_1", 7L, 2_000L);
        verify(ipProxyService).markFailedProxyUnavailable(100L, 7L, 2_000L);
        verifyNoInteractions(onlineCommandService);
    }
}
