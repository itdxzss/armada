package com.armada.marketing.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 营销任务到时启动/结束与账号租约的事务联动测试。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTaskLifecycleWorkerTest {

    @Mock
    private MarketingTaskMapper taskMapper;

    @Mock
    private MarketingAccountOccupancyService occupancyService;

    @InjectMocks
    private MarketingTaskLifecycleWorker worker;

    @Test
    void startDueWaitingTask_updatedTask_acquiresAvailableAccounts() {
        MarketingTask task = task();
        when(taskMapper.startDueWaitingTask(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);

        worker.startDueWaitingTask(1L, 42L);

        verify(occupancyService).acquireAndLoadTaskAccounts(
                org.mockito.ArgumentMatchers.eq(task), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void startDueWaitingTask_notUpdated_doesNotAcquireAccounts() {
        worker.startDueWaitingTask(1L, 42L);

        verify(occupancyService, never()).acquireAndLoadTaskAccounts(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void endExpiredTask_updatedTask_releasesOwnedAccounts() {
        when(taskMapper.endExpiredTask(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        worker.endExpiredTask(1L, 42L);

        verify(taskMapper).markTaskWaitingAttemptsSkipped(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("TASK_EXPIRED"),
                org.mockito.ArgumentMatchers.eq("营销任务已结束"),
                org.mockito.ArgumentMatchers.anyLong());
        verify(occupancyService).releaseTaskAccounts(42L);
    }

    private static MarketingTask task() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setStatus(MarketingTaskStatus.SENDING.code());
        return task;
    }
}
