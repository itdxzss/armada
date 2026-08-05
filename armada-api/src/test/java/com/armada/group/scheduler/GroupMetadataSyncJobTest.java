package com.armada.group.scheduler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupMetadataSyncMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncExecutor;
import com.armada.group.service.GroupMetadataSyncTaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群详情同步调度 job 单测。 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataSyncJobTest {

    @Mock
    private GroupMetadataSyncTaskService taskService;

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private GroupMetadataSyncExecutor executor;

    @Test
    void runOnceDefersWithoutAccountAndExecutesClaimedTasksIndependently() {
        GroupMetadataSyncTask deferred = task(1L, 7L, 10L);
        GroupMetadataSyncTask executable = task(2L, 8L, 20L);
        GroupExecutionAccount account = new GroupExecutionAccount(77L, null, "acc_77", "acc_77", true);
        when(taskService.findDue(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(deferred, executable));
        when(selector.find(10L)).thenReturn(Optional.empty());
        when(selector.find(20L)).thenReturn(Optional.of(account));
        when(taskService.claim(
                org.mockito.ArgumentMatchers.eq(executable),
                org.mockito.ArgumentMatchers.eq(account),
                anyLong(),
                anyLong(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        GroupMetadataSyncJob job = job();

        job.runOnce();

        verify(taskService).defer(org.mockito.ArgumentMatchers.eq(deferred), anyLong());
        verify(executor).execute(executable, account);
        verify(taskService).succeed(org.mockito.ArgumentMatchers.eq(executable), anyLong());
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.eq(deferred),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runOnceRecordsOneFailureWithoutStoppingLaterCandidates() {
        GroupMetadataSyncTask failed = task(1L, 7L, 10L);
        GroupMetadataSyncTask succeeded = task(2L, 7L, 20L);
        GroupExecutionAccount account1 = new GroupExecutionAccount(71L, null, "acc_71", "acc_71", true);
        GroupExecutionAccount account2 = new GroupExecutionAccount(72L, null, "acc_72", "acc_72", true);
        when(taskService.findDue(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(failed, succeeded));
        when(selector.find(10L)).thenReturn(Optional.of(account1));
        when(selector.find(20L)).thenReturn(Optional.of(account2));
        when(taskService.claim(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive remote message"))
                .when(executor).execute(failed, account1);

        job().runOnce();

        verify(taskService).fail(
                org.mockito.ArgumentMatchers.eq(failed),
                org.mockito.ArgumentMatchers.eq("IllegalStateException"),
                org.mockito.ArgumentMatchers.eq("群详情同步执行失败"),
                anyLong());
        verify(taskService).succeed(org.mockito.ArgumentMatchers.eq(succeeded), anyLong());
    }

    private GroupMetadataSyncJob job() {
        return new GroupMetadataSyncJob(
                taskService,
                selector,
                executor,
                new GroupMetadataSyncJobProperties(true, 5_000L, 20, 120_000L, 2_000L, 3, 1),
                new GroupMetadataSyncMetrics());
    }

    private static GroupMetadataSyncTask task(long id, long tenantId, long groupLinkId) {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setId(id);
        task.setTenantId(tenantId);
        task.setGroupLinkId(groupLinkId);
        task.setAttemptCount(0);
        return task;
    }
}
