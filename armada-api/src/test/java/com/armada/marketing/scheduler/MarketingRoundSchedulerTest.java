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
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setEnabled(true);
        properties.setExecutorPoolSize(5);
        properties.setScanLimit(20);

        MarketingTask task = new MarketingTask();
        task.setTenantId(1L);
        task.setId(42L);
        when(mapper.selectDueSendingTasks(anyLong(), eq(20))).thenReturn(List.of(task));

        MarketingRoundScheduler scheduler = new MarketingRoundScheduler(mapper, worker, properties);
        scheduler.scanDueTasks();

        verify(worker, timeout(1_000)).runRound(1L, 42L);
        scheduler.shutdown();
    }
}
