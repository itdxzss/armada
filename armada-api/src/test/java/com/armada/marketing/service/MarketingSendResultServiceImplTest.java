package com.armada.marketing.service;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.service.impl.MarketingSendResultServiceImpl;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSendResultServiceImplTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final MarketingSendResultServiceImpl service = new MarketingSendResultServiceImpl(mapper);

    @AfterEach
    void clearTenant() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void successEventUpdatesAttemptAndIncrementsSuccessCountOnce() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(9001L, "wamid.1", 1783159200000L);
        verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
    }

    @Test
    void duplicateSuccessEventDoesNotIncrementCountersAgain() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", 1783159200000L)).thenReturn(0);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(9001L, "wamid.1", 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
    }

    private static ProtocolMessageSendResultReportedEvent event(boolean success) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_1",
                1L,
                42L,
                501L,
                9001L,
                1L,
                "acc_8613800138000",
                "120363001@g.us",
                "cmd_1",
                success,
                "wamid.1",
                null,
                null,
                1783159200000L,
                "worker-a");
    }
}
