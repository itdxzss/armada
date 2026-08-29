package com.armada.hyperlink.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkDispatchService;
import com.armada.hyperlink.task.service.HyperlinkMetricsProjectionService;
import com.armada.hyperlink.task.service.HyperlinkProvisioningService;
import com.armada.hyperlink.task.service.HyperlinkRoundLifecycleService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 单个坏任务不能阻断同一扫描批次里的后续候选。 */
class HyperlinkSchedulerCandidateIsolationTest {
    private static final HyperlinkProvisionCandidate FIRST =
            new HyperlinkProvisionCandidate(7L, 11L);
    private static final HyperlinkProvisionCandidate SECOND =
            new HyperlinkProvisionCandidate(8L, 12L);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void dispatchContinuesAfterOneCandidateFailsAndRestoresTenant() {
        HyperlinkTaskRoundMapper mapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkDispatchService service = mock(HyperlinkDispatchService.class);
        when(mapper.selectDispatchCandidates(anyLong(), eq(200)))
                .thenReturn(List.of(FIRST, SECOND));
        doThrow(new IllegalStateException("first failed")).when(service).dispatchOne(11L);
        TenantContext.set(99L);

        new HyperlinkDispatchScheduler(mapper, service).dispatch();

        verify(service).dispatchOne(11L);
        verify(service).dispatchOne(12L);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void dispatchesAtMostFiftyRecipientsPerTaskAndTick() {
        HyperlinkTaskRoundMapper mapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkDispatchService service = mock(HyperlinkDispatchService.class);
        when(mapper.selectDispatchCandidates(anyLong(), eq(200))).thenReturn(List.of(FIRST));
        when(service.dispatchOne(11L)).thenReturn(true);

        new HyperlinkDispatchScheduler(mapper, service).dispatch();

        verify(service, times(50)).dispatchOne(11L);
    }

    @Test
    void dispatchBatchStopsWhenCurrentCapacityIsExhausted() {
        HyperlinkTaskRoundMapper mapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkDispatchService service = mock(HyperlinkDispatchService.class);
        when(mapper.selectDispatchCandidates(anyLong(), eq(200))).thenReturn(List.of(FIRST));
        when(service.dispatchOne(11L)).thenReturn(true, true, true, false);

        new HyperlinkDispatchScheduler(mapper, service).dispatch();

        verify(service, times(4)).dispatchOne(11L);
    }

    @Test
    void provisioningContinuesAfterOneCandidateFailsAndRestoresTenant() {
        HyperlinkTaskRecipientClaimMapper mapper = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkProvisioningService service = mock(HyperlinkProvisioningService.class);
        when(mapper.selectProvisionCandidates(100)).thenReturn(List.of(FIRST, SECOND));
        doThrow(new IllegalStateException("first failed")).when(service).advance(11L);
        TenantContext.set(99L);

        new HyperlinkProvisioningScheduler(mapper, service).advanceDue();

        verify(service).advance(11L);
        verify(service).advance(12L);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void roundStartContinuesAfterOneCandidateFailsAndRestoresTenant() {
        HyperlinkTaskRoundMapper roundMapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkRoundLifecycleService lifecycleService = mock(HyperlinkRoundLifecycleService.class);
        when(roundMapper.selectStartCandidates(anyLong(), eq(100)))
                .thenReturn(List.of(FIRST, SECOND));
        doThrow(new IllegalStateException("first failed")).when(lifecycleService).startDue(11L);
        TenantContext.set(99L);

        new HyperlinkRoundStartScheduler(roundMapper, lifecycleService).startDueRounds();

        verify(lifecycleService).startDue(11L);
        verify(lifecycleService).startDue(12L);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void projectionProcessesOnlyBoundedBatchesPerTick() {
        HyperlinkMetricsProjectionService service = mock(HyperlinkMetricsProjectionService.class);
        when(service.projectNextBatch())
                .thenReturn(HyperlinkMetricsProjectionService.BATCH_SIZE);

        new HyperlinkMetricsProjectionScheduler(service).project();

        verify(service, times(HyperlinkMetricsProjectionScheduler.MAX_BATCHES_PER_TICK))
                .projectNextBatch();
        assertThat(HyperlinkMetricsProjectionScheduler.MAX_BATCHES_PER_TICK
                * HyperlinkMetricsProjectionService.BATCH_SIZE).isEqualTo(4_000);
    }
}
