package com.armada.marketing.scheduler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketingNewGroupDelaySchedulerTest {

    @Test
    void scanGroupsDueAttemptsByTenantTaskAndTarget() {
        MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
        MarketingNewGroupImmediateSendService sendService = mock(MarketingNewGroupImmediateSendService.class);
        MarketingRoundSchedulerProperties properties = enabledProperties();
        when(mapper.selectDueWaitingNewGroupAttempts(anyLong(), eq(20))).thenReturn(List.of(
                attempt(1L, 41L, 501L, 9_001L),
                attempt(1L, 41L, 501L, 9_002L),
                attempt(1L, 41L, 502L, 9_003L),
                attempt(2L, 42L, 601L, 9_004L)));

        new MarketingNewGroupDelayScheduler(mapper, sendService, properties).scanDueWaitingAttempts();

        verify(sendService).submitDueWaitingAttempts(
                eq(1L), eq(41L), eq(List.of(9_001L, 9_002L)), anyLong());
        verify(sendService).submitDueWaitingAttempts(
                eq(1L), eq(41L), eq(List.of(9_003L)), anyLong());
        verify(sendService).submitDueWaitingAttempts(
                eq(2L), eq(42L), eq(List.of(9_004L)), anyLong());
    }

    @Test
    void disabledSchedulerDoesNotScan() {
        MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
        MarketingNewGroupImmediateSendService sendService = mock(MarketingNewGroupImmediateSendService.class);
        MarketingRoundSchedulerProperties properties = enabledProperties();
        properties.setEnabled(false);

        new MarketingNewGroupDelayScheduler(mapper, sendService, properties).scanDueWaitingAttempts();

        verify(mapper, never()).selectDueWaitingNewGroupAttempts(anyLong(), eq(20));
        verifyNoInteractions(sendService);
    }

    private static MarketingRoundSchedulerProperties enabledProperties() {
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setEnabled(true);
        properties.setScanLimit(20);
        return properties;
    }

    private static MarketingTaskSendAttempt attempt(Long tenantId, Long taskId, Long targetId, Long id) {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setTenantId(tenantId);
        attempt.setMarketingTaskId(taskId);
        attempt.setTargetId(targetId);
        attempt.setId(id);
        return attempt;
    }
}
