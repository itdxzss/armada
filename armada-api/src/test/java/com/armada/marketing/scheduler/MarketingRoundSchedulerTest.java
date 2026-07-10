package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingRoundSchedulerTest {

    @Test
    void scanSubmitsDueTasksToWorker() {
        MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
        MarketingRoundWorker worker = mock(MarketingRoundWorker.class);
        MarketingTaskLifecycleWorker lifecycleWorker = mock(MarketingTaskLifecycleWorker.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setEnabled(true);
        properties.setExecutorPoolSize(5);
        properties.setScanLimit(20);

        MarketingTask task = new MarketingTask();
        task.setTenantId(1L);
        task.setId(42L);
        when(mapper.selectDueSendingTasks(anyLong(), eq(20))).thenReturn(List.of(task));

        MarketingRoundScheduler scheduler = new MarketingRoundScheduler(mapper, worker, lifecycleWorker, properties);
        scheduler.scanDueTasks();

        verify(worker, timeout(1_000)).runRound(1L, 42L);
        scheduler.shutdown();
    }

    @Test
    void scanProcessesLifecycleTransitionsBeforeDueRounds() {
        MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
        MarketingRoundWorker worker = mock(MarketingRoundWorker.class);
        MarketingTaskLifecycleWorker lifecycleWorker = mock(MarketingTaskLifecycleWorker.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setEnabled(true);
        properties.setExecutorPoolSize(5);
        properties.setScanLimit(20);

        MarketingTask expired = task(1L, 41L);
        MarketingTask waiting = task(1L, 42L);
        when(mapper.selectExpiredRunnableTasks(anyLong(), eq(20))).thenReturn(List.of(expired));
        when(mapper.selectDueWaitingTasks(anyLong(), eq(20))).thenReturn(List.of(waiting));

        MarketingRoundScheduler scheduler = new MarketingRoundScheduler(mapper, worker, lifecycleWorker, properties);
        scheduler.scanDueTasks();

        verify(lifecycleWorker, timeout(1_000)).endExpiredTask(1L, 41L);
        verify(lifecycleWorker, timeout(1_000)).startDueWaitingTask(1L, 42L);
        scheduler.shutdown();
    }

    private static MarketingTask task(Long tenantId, Long id) {
        MarketingTask task = new MarketingTask();
        task.setTenantId(tenantId);
        task.setId(id);
        return task;
    }
}
