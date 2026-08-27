package com.armada.account.recovery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void openScope() {
        DataScopeContext.open(DataScope.self(10L));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void recover_marksExactFailedProxyUnavailableBeforeStartingIndependentReonlineTransaction() {
        ProxyFailedRecoveryCoordinator coordinator = coordinator();

        coordinator.recover(1L, 100L, "oa_failed_1", 7L);

        InOrder inOrder = inOrder(ipProxyService, onlineCommandService);
        inOrder.verify(ipProxyService).markFailedProxyUnavailable(100L, 7L);
        inOrder.verify(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L);
    }

    @Test
    void recover_markUnavailableFailureDoesNotPreventReonlineOrEscapeToKafkaConsumer() {
        doThrow(new IllegalStateException("release failed"))
                .when(ipProxyService).markFailedProxyUnavailable(100L, 7L);

        assertThatCode(() -> coordinator().recover(1L, 100L, "oa_failed_1", 7L))
                .doesNotThrowAnyException();

        verify(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L);
    }

    @Test
    void recover_reonlineFailureDoesNotEscapeAndRestoresPreviousTenant() {
        doThrow(new IllegalStateException("no idle proxy"))
                .when(onlineCommandService).reonlineAfterProxyFailure(100L, "oa_failed_1", 7L);
        TenantContext.set(99L);

        assertThatCode(() -> coordinator().recover(1L, 100L, "oa_failed_1", 7L))
                .doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }

    private ProxyFailedRecoveryCoordinator coordinator() {
        return new ProxyFailedRecoveryCoordinator(ipProxyService, onlineCommandService);
    }
}
