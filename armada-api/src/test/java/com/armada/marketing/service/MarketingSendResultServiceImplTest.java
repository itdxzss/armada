package com.armada.marketing.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.armada.marketing.service.impl.MarketingSendResultServiceImpl;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSendResultServiceImplTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final GroupCreationMarketingTaskMapper groupCreationMapper = mock(GroupCreationMarketingTaskMapper.class);
    private final GroupCreationMarketingRetryService retryService = mock(GroupCreationMarketingRetryService.class);
    private final MarketingSendResultServiceImpl service =
            new MarketingSendResultServiceImpl(mapper, groupCreationMapper, retryService);

    @AfterEach
    void clearTenant() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void successEventUpdatesAttemptAndIncrementsSuccessCountOnce() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L);
        verify(mapper).markTargetSuccessFromAttempt(501L, 9001L, 1783159200000L);
        verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(groupCreationMapper).markItemSuccessByMarketingAttemptId(9001L, 1783159200000L);
    }

    @Test
    void failedEventUpdatesAttemptTargetAndIncrementsFailureCountOnce() {
        ProtocolMessageSendResultReportedEvent event = failedEvent();
        when(mapper.markAttemptFailed(9001L, "SEND_FAILED", "rate limited",
                "120363001@g.us", 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptFailed(9001L, "SEND_FAILED", "rate limited",
                "120363001@g.us", 1783159200000L);
        verify(mapper).markTargetFailedFromAttempt(501L, 9001L, "SEND_FAILED", "rate limited", 1783159200000L);
        verify(mapper).incrementTaskSendCounters(42L, 0, 1, 1783159200000L);
        verify(groupCreationMapper).markItemFailedByMarketingAttemptId(9001L, "SEND_FAILED", "rate limited", 1783159200000L);
    }

    @Test
    void successEventLogsAppliedResult() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingSendResultServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolMessageSendResultReportedEvent event = event(true);
            when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(1);

            service.handleSendResultReported(event);

            assertThat(appender.list)
                    .anyMatch(log -> log.getFormattedMessage().contains("营销发送结果已回写")
                            && log.getFormattedMessage().contains("tenantId=1")
                            && log.getFormattedMessage().contains("taskId=42")
                            && log.getFormattedMessage().contains("attemptId=9001")
                            && log.getFormattedMessage().contains("commandId=cmd_1")
                            && log.getFormattedMessage().contains("success=true"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void duplicateSuccessEventDoesNotIncrementCountersAgain() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(0);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L);
        verify(mapper, never()).markTargetSuccessFromAttempt(501L, 9001L, 1783159200000L);
        verify(mapper, never()).markTargetFailedFromAttempt(501L, 9001L, null, null, 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(groupCreationMapper, never()).markItemSuccessByMarketingAttemptId(9001L, 1783159200000L);
    }

    @Test
    void groupCreationSuccessEventUpdatesItemByCommandIdWithoutMarketingTables() {
        ProtocolMessageSendResultReportedEvent event = groupCreationEvent(true);
        when(groupCreationMapper.markItemSuccessByCommandId(
                11L, "cmd_gcm_item_11", "120363001@g.us", "wamid.1", 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(groupCreationMapper).markItemSuccessByCommandId(
                11L, "cmd_gcm_item_11", "120363001@g.us", "wamid.1", 1783159200000L);
        verify(mapper, never()).markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
    }

    @Test
    void groupCreationFailedEventSchedulesAccountRetryWithoutMarketingTables() {
        ProtocolMessageSendResultReportedEvent event = groupCreationEvent(false);
        GroupCreationMarketingItem item = groupCreationItem();
        GroupCreationMarketingTask task = groupCreationTask();
        when(groupCreationMapper.selectItemById(11L)).thenReturn(item);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(retryService.resetMarketingSendingItemForAccountRetry(
                item, task, "cmd_gcm_item_11", "SEND_FAILED", "rate limited", 1783159200000L)).thenReturn(true);

        service.handleSendResultReported(event);

        verify(retryService).resetMarketingSendingItemForAccountRetry(
                item, task, "cmd_gcm_item_11", "SEND_FAILED", "rate limited", 1783159200000L);
        verify(groupCreationMapper, never()).markItemFailedByCommandId(
                11L, "cmd_gcm_item_11", "SEND_FAILED", "rate limited", 1783159200000L);
        verify(mapper, never()).markAttemptFailed(9001L, "SEND_FAILED", "rate limited",
                "120363001@g.us", 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 0, 1, 1783159200000L);
    }

    @Test
    void staleGroupCreationFailureEventDoesNotRetryChangedItem() {
        ProtocolMessageSendResultReportedEvent event = groupCreationEvent(false);
        GroupCreationMarketingItem item = groupCreationItem();
        item.setCommandId("new_cmd");
        when(groupCreationMapper.selectItemById(11L)).thenReturn(item);

        service.handleSendResultReported(event);

        verify(retryService, never()).resetMarketingSendingItemForAccountRetry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        verify(groupCreationMapper, never()).markItemFailedByCommandId(
                11L, "cmd_gcm_item_11", "SEND_FAILED", "rate limited", 1783159200000L);
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
                "worker-a",
                null,
                null,
                "marketing_task");
    }

    private static ProtocolMessageSendResultReportedEvent failedEvent() {
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
                false,
                null,
                "SEND_FAILED",
                "rate limited",
                1783159200000L,
                "worker-a",
                null,
                null,
                "marketing_task");
    }

    private static ProtocolMessageSendResultReportedEvent groupCreationEvent(boolean success) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_gcm_1",
                1L,
                null,
                null,
                null,
                null,
                "acc_8613800138000",
                "120363001@g.us",
                "cmd_gcm_item_11",
                success,
                success ? "wamid.1" : null,
                success ? null : "SEND_FAILED",
                success ? null : "rate limited",
                1783159200000L,
                "worker-a",
                22L,
                11L,
                "group_creation_marketing");
    }

    private static GroupCreationMarketingItem groupCreationItem() {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setId(11L);
        item.setTaskId(22L);
        item.setAccountId(7L);
        item.setAccountPhone("8613000000000");
        item.setProtocolAccountId("acc_7");
        item.setCommandId("cmd_gcm_item_11");
        item.setStatus(GroupCreationMarketingItemStatus.MARKETING_SENDING.code());
        return item;
    }

    private static GroupCreationMarketingTask groupCreationTask() {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setId(22L);
        task.setAccountGroupId(8L);
        return task;
    }
}
