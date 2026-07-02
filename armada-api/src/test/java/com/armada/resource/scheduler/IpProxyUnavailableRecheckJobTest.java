package com.armada.resource.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.resource.service.IpProxyRecheckResult;
import com.armada.resource.service.IpProxyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 不可用 IP 定时重检任务单测。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyUnavailableRecheckJobTest {

    @Mock
    private IpProxyService service;

    @Test
    void runOnce_disabled_skipsServiceCall() {
        IpProxyUnavailableRecheckJob job = new IpProxyUnavailableRecheckJob(
                service, new IpProxyUnavailableRecheckJobProperties(false, 900_000L, 20));

        IpProxyUnavailableRecheckJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isZero();
        assertThat(result.checked()).isZero();
        assertThat(result.failed()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void runOnce_enabledDelegatesToService() {
        when(service.recheckUnavailableProxies(20))
                .thenReturn(new IpProxyRecheckResult(3, 3, 1));
        IpProxyUnavailableRecheckJob job = new IpProxyUnavailableRecheckJob(
                service, new IpProxyUnavailableRecheckJobProperties(true, 900_000L, 20));

        IpProxyUnavailableRecheckJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.checked()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
        verify(service).recheckUnavailableProxies(20);
    }
}
