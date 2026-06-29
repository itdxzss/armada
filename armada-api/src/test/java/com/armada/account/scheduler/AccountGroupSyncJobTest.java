package com.armada.account.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountGroupSyncCommandService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 账号当前群同步定时任务单测:只验调度开关和 service 调用。 */
class AccountGroupSyncJobTest {

    private final AccountGroupSyncCommandService service = Mockito.mock(AccountGroupSyncCommandService.class);

    @Test
    void runOnce_disabled_skipsServiceCall() {
        AccountGroupSyncJob job = new AccountGroupSyncJob(
                service, new AccountGroupSyncJobProperties(false, 180_000, 50));

        AccountGroupSyncJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isZero();
        assertThat(result.enqueued()).isZero();
        assertThat(result.tenantBatches()).isZero();
        verify(service, never()).enqueueDueSyncCommands(Mockito.anyInt());
    }

    @Test
    void runOnce_enabledDelegatesToService() {
        when(service.enqueueDueSyncCommands(200))
                .thenReturn(new AccountGroupSyncCommandService.EnqueueResult(10, 8, 2));
        AccountGroupSyncJob job = new AccountGroupSyncJob(
                service, new AccountGroupSyncJobProperties(true, 180_000, 200));

        AccountGroupSyncJob.JobResult result = job.runOnce();

        assertThat(result.scanned()).isEqualTo(10);
        assertThat(result.enqueued()).isEqualTo(8);
        assertThat(result.tenantBatches()).isEqualTo(2);
        verify(service).enqueueDueSyncCommands(200);
    }
}
