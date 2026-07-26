package com.armada.account.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.AccountProxyFailedRecoveryCandidate;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountProxyFailureContext;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyFailedRecoveryDispatcherTest {

    @Mock
    private AccountStateMapper stateMapper;

    @Mock
    private AccountOnlineAttemptLogService attemptLogService;

    @Mock
    private ProxyFailedRecoveryCoordinator coordinator;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void dispatchOnce_retriesDurableOfflineProxyFailedCandidatesAcrossTenants() {
        long now = 10_000L;
        when(stateMapper.selectProxyFailedRecoveryCandidates(2, "PROXY_FAILED", 2, 5_000L, 1_000))
                .thenReturn(List.of(
                        new AccountProxyFailedRecoveryCandidate(1L, 100L),
                        new AccountProxyFailedRecoveryCandidate(2L, 200L)));
        when(attemptLogService.latestProxyFailure(100L))
                .thenReturn(new AccountProxyFailureContext("oa_100", 7L));
        when(attemptLogService.latestProxyFailure(200L))
                .thenReturn(new AccountProxyFailureContext("oa_200", 8L));
        TenantContext.set(99L);

        int attempted = dispatcher().dispatchOnce(now);

        assertThat(attempted).isEqualTo(2);
        verify(coordinator).recover(1L, 100L, "oa_100", 7L);
        verify(coordinator).recover(2L, 200L, "oa_200", 8L);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void dispatchOnce_oneCandidateFailureDoesNotBlockLaterAccounts() {
        when(stateMapper.selectProxyFailedRecoveryCandidates(2, "PROXY_FAILED", 2, 5_000L, 1_000))
                .thenReturn(List.of(
                        new AccountProxyFailedRecoveryCandidate(1L, 100L),
                        new AccountProxyFailedRecoveryCandidate(1L, 101L)));
        doThrow(new IllegalStateException("unexpected"))
                .when(coordinator).recover(1L, 100L, null, null);

        int attempted = dispatcher().dispatchOnce(10_000L);

        assertThat(attempted).isEqualTo(2);
        verify(coordinator).recover(1L, 101L, null, null);
    }

    private ProxyFailedRecoveryDispatcher dispatcher() {
        return new ProxyFailedRecoveryDispatcher(stateMapper, attemptLogService, coordinator);
    }
}
