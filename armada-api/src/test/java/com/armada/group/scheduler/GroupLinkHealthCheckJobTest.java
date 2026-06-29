package com.armada.group.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupLinkHealthCheckService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 群链接健康检查定时任务单测:只验调度开关和 service 调用。 */
class GroupLinkHealthCheckJobTest {

    private final GroupLinkHealthCheckService service = Mockito.mock(GroupLinkHealthCheckService.class);

    @Test
    void runOnce_disabled_skipsServiceCall() {
        GroupLinkHealthCheckJob job = new GroupLinkHealthCheckJob(
                service, new GroupLinkHealthCheckJobProperties(false, 180_000, 50));

        GroupLinkHealthCheckJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isZero();
        assertThat(result.enqueued()).isZero();
        assertThat(result.tenantBatches()).isZero();
        verify(service, never()).enqueueDueHealthChecks(Mockito.anyInt());
    }

    @Test
    void runOnce_enabledDelegatesToService() {
        when(service.enqueueDueHealthChecks(200))
                .thenReturn(new GroupLinkHealthCheckService.EnqueueResult(10, 8, 2));
        GroupLinkHealthCheckJob job = new GroupLinkHealthCheckJob(
                service, new GroupLinkHealthCheckJobProperties(true, 180_000, 200));

        GroupLinkHealthCheckJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isEqualTo(10);
        assertThat(result.enqueued()).isEqualTo(8);
        assertThat(result.tenantBatches()).isEqualTo(2);
        verify(service).enqueueDueHealthChecks(200);
    }
}
