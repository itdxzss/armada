package com.armada.marketing.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.armada.marketing.service.impl.MarketingImmediateRetryService;
import com.armada.marketing.service.impl.MarketingSendResultServiceImpl;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.security.DataScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSendResultServiceImplTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final GroupCreationMarketingTaskMapper groupCreationMapper = mock(GroupCreationMarketingTaskMapper.class);
    private final GroupCreationMarketingRetryService retryService = mock(GroupCreationMarketingRetryService.class);
    private final MarketingImmediateRetryService immediateRetryService = mock(MarketingImmediateRetryService.class);
    private final MarketingSendResultServiceImpl service =
            new MarketingSendResultServiceImpl(mapper, groupCreationMapper, retryService, immediateRetryService);

    @BeforeEach
    void setUpTrustedRoots() {
        when(mapper.selectSendAttemptById(9001L)).thenReturn(marketingAttempt());
        when(mapper.selectTaskById(42L)).thenReturn(marketingTask());
        when(groupCreationMapper.selectItemById(11L)).thenReturn(groupCreationItem());
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(groupCreationTask());
    }

    @AfterEach
    void clearContexts() {
        com.armada.shared.tenant.TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void successEventUpdatesAttemptAndIncrementsSuccessCountOnce() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(successResult())).thenAnswer(invocation -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(7L);
            return 1;
        });
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L)).thenReturn(1);
        when(mapper.incrementTaskSuccessfulGroupCount(42L, 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(successResult());
        verify(mapper).markTargetSuccessFromAttempt(501L, 9001L, 1783159200000L);
        verify(mapper).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper).insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L);
        verify(mapper).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
        verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(groupCreationMapper).markItemSuccessByMarketingAttemptId(9001L, 1783159200000L);
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void failedEventUpdatesAttemptTargetAndIncrementsFailureCountOnce() {
        ProtocolMessageSendResultReportedEvent event = failedEvent();
        when(mapper.markAttemptFailed(failedResult())).thenReturn(1);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptFailed(failedResult());
        verify(mapper).markTargetFailedFromAttempt(501L, 9001L, "SEND_FAILED", "rate limited", 1783159200000L);
        verify(mapper, never()).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper, never()).insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
        verify(mapper).incrementTaskSendCounters(42L, 0, 1, 1783159200000L);
        verify(groupCreationMapper).markItemFailedByMarketingAttemptId(9001L, "SEND_FAILED", "rate limited", 1783159200000L);
        verify(immediateRetryService).retryIfEligible(event, 1783159200000L);
    }

    @Test
    void immediateFailureEnteringRetryDoesNotFinalizeOriginalResult() {
        ProtocolMessageSendResultReportedEvent event = immediateFailedEvent();
        when(immediateRetryService.retryIfEligible(event, 1783159200000L)).thenReturn(true);

        service.handleSendResultReported(event);

        verify(immediateRetryService).retryIfEligible(event, 1783159200000L);
        verify(mapper, never()).markAttemptFailed(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).markTargetFailedFromAttempt(
                501L, 9001L, "SEND_FAILED", "rate limited", 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 0, 1, 1783159200000L);
    }

    @Test
    void successEventLogsAppliedResult() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingSendResultServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolMessageSendResultReportedEvent event = event(true);
            when(mapper.markAttemptSuccess(successResult())).thenReturn(1);
            when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
            when(mapper.insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L)).thenReturn(1);
            when(mapper.incrementTaskSuccessfulGroupCount(42L, 1783159200000L)).thenReturn(1);

            service.handleSendResultReported(event);

            assertThat(appender.list)
                    .anyMatch(log -> log.getFormattedMessage().contains("营销发送结果已回写")
                            && log.getFormattedMessage().contains("tenantId=1")
                            && log.getFormattedMessage().contains("taskId=42")
                            && log.getFormattedMessage().contains("attemptId=9001")
                            && log.getFormattedMessage().contains("commandId=cmd_1")
                            && log.getFormattedMessage().contains("success=true")
                            && log.getFormattedMessage().contains("newSuccessfulGroup=true"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void duplicateSuccessEventDoesNotIncrementCountersAgain() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(successResult())).thenReturn(0);

        service.handleSendResultReported(event);

        verify(mapper).markAttemptSuccess(successResult());
        verify(mapper, never()).markTargetSuccessFromAttempt(501L, 9001L, 1783159200000L);
        verify(mapper, never()).markTargetFailedFromAttempt(501L, 9001L, null, null, 1783159200000L);
        verify(mapper, never()).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper, never()).insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
        verify(mapper, never()).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(groupCreationMapper, never()).markItemSuccessByMarketingAttemptId(9001L, 1783159200000L);
    }

    @Test
    void laterSuccessfulAttemptForCountedGroupDoesNotIncrementGroupCount() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(successResult())).thenReturn(1);
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L)).thenReturn(0);

        service.handleSendResultReported(event);

        verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
    }

    @Test
    void successfulAttemptWithoutPersistedGroupJidDoesNotIncrementGroupCount() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingSendResultServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolMessageSendResultReportedEvent event = event(true);
            when(mapper.markAttemptSuccess(successResult())).thenReturn(1);
            when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn(null);

            service.handleSendResultReported(event);

            verify(mapper, never()).insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L);
            verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
            verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
            assertThat(appender.list)
                    .anyMatch(log -> log.getFormattedMessage().contains("营销成功结果缺少有效群JID")
                            && log.getFormattedMessage().contains("attemptId=9001"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void successfulGroupFactRollsBackWhenTaskCounterCannotBeUpdated() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(successResult())).thenReturn(1);
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(1L, 42L, 9001L, 1783159200000L)).thenReturn(1);
        when(mapper.incrementTaskSuccessfulGroupCount(42L, 1783159200000L)).thenReturn(0);

        assertThatThrownBy(() -> service.handleSendResultReported(event))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("累计成功群组数量更新失败");
    }

    @Test
    void groupCreationSuccessEventUpdatesItemByCommandIdWithoutMarketingTables() {
        ProtocolMessageSendResultReportedEvent event = groupCreationEvent(true);
        when(groupCreationMapper.markItemSuccessByCommandId(
                11L, "cmd_gcm_item_11", "120363001@g.us", "wamid.1", 1783159200000L)).thenReturn(1);

        service.handleSendResultReported(event);

        verify(groupCreationMapper).markItemSuccessByCommandId(
                11L, "cmd_gcm_item_11", "120363001@g.us", "wamid.1", 1783159200000L);
        verify(mapper, never()).markAttemptSuccess(org.mockito.ArgumentMatchers.any());
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
        verify(mapper, never()).markAttemptFailed(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).incrementTaskSendCounters(42L, 0, 1, 1783159200000L);
        verify(immediateRetryService, never()).retryIfEligible(event, 1783159200000L);
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

    @Test
    void forgedMarketingTaskIdDoesNotMutateTrustedAttempt() {
        ProtocolMessageSendResultReportedEvent event = marketingEventWithTaskId(43L);

        service.handleSendResultReported(event);

        verify(mapper, never()).selectTaskById(42L);
        verify(immediateRetryService, never()).retryIfEligible(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).markAttemptSuccess(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).markAttemptFailed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void historicalOwnerlessMarketingTaskDoesNotAcceptResult() {
        MarketingTask task = marketingTask();
        task.setOwnerUserId(null);
        when(mapper.selectTaskById(42L)).thenReturn(task);

        service.handleSendResultReported(event(true));

        verify(mapper, never()).markAttemptSuccess(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).incrementTaskSendCounters(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void forgedGroupCreationTaskIdDoesNotMutateTrustedItem() {
        ProtocolMessageSendResultReportedEvent event = groupCreationEventWithTaskId(23L);

        service.handleSendResultReported(event);

        verify(groupCreationMapper, never()).selectTaskById(22L);
        verify(groupCreationMapper, never()).markItemSuccessByCommandId(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void supportsExistingSourcesButExplicitlyRejectsHistoricalGroupPull() {
        assertThat(service.supports(event(true))).isTrue();
        assertThat(service.supports(groupCreationEvent(true))).isTrue();
        ProtocolMessageSendResultReportedEvent historical = new ProtocolMessageSendResultReportedEvent(
                "evt_historical",
                1L,
                null,
                null,
                null,
                null,
                "acc_1",
                "120363history@g.us",
                "cmd_historical",
                false,
                null,
                "SEND_FAILED",
                "administrator permission denied",
                1783159200000L,
                "worker-a",
                null,
                null,
                "historical_group_pull",
                "UNCONFIRMED",
                "PRECHECK_SKIPPED_BY_SOURCE",
                1783159199000L,
                91L,
                301L);

        assertThat(service.supports(historical)).isFalse();
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
                "marketing_task",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                1783159199000L,
                null,
                null);
    }

    private static ProtocolMessageSendResultReportedEvent marketingEventWithTaskId(Long taskId) {
        ProtocolMessageSendResultReportedEvent event = event(true);
        return new ProtocolMessageSendResultReportedEvent(
                event.eventId(),
                event.tenantId(),
                taskId,
                event.targetId(),
                event.attemptId(),
                event.roundNo(),
                event.protocolAccountId(),
                event.groupJid(),
                event.commandId(),
                event.success(),
                event.messageId(),
                event.reasonCode(),
                event.reasonMessage(),
                event.timestamp(),
                event.workerId(),
                event.groupCreationTaskId(),
                event.groupCreationItemId(),
                event.source(),
                event.groupStatus(),
                event.groupStatusReason(),
                event.groupStatusCheckedAt(),
                event.historicalExecutionId(),
                event.historicalMemberId());
    }

    private static MarketingSendAttemptResult successResult() {
        return new MarketingSendAttemptResult(
                9_001L,
                "cmd_1",
                "wamid.1",
                null,
                null,
                "120363001@g.us",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                1_783_159_199_000L,
                1_783_159_200_000L);
    }

    private static MarketingSendAttemptResult failedResult() {
        return new MarketingSendAttemptResult(
                9_001L,
                "cmd_1",
                null,
                "SEND_FAILED",
                "rate limited",
                "120363001@g.us",
                "BANNED",
                "CHAT_SUSPENDED",
                1_783_159_198_000L,
                1_783_159_200_000L);
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
                "marketing_task",
                "BANNED",
                "CHAT_SUSPENDED",
                1783159198000L,
                null,
                null);
    }

    private static ProtocolMessageSendResultReportedEvent immediateFailedEvent() {
        ProtocolMessageSendResultReportedEvent event = failedEvent();
        return new ProtocolMessageSendResultReportedEvent(
                event.eventId(),
                event.tenantId(),
                event.marketingTaskId(),
                event.targetId(),
                event.attemptId(),
                0L,
                event.protocolAccountId(),
                event.groupJid(),
                event.commandId(),
                event.success(),
                event.messageId(),
                event.reasonCode(),
                event.reasonMessage(),
                event.timestamp(),
                event.workerId(),
                event.groupCreationTaskId(),
                event.groupCreationItemId(),
                event.source(),
                event.groupStatus(),
                event.groupStatusReason(),
                event.groupStatusCheckedAt(),
                event.historicalExecutionId(),
                event.historicalMemberId());
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
                "group_creation_marketing",
                null,
                null,
                null,
                null,
                null);
    }

    private static ProtocolMessageSendResultReportedEvent groupCreationEventWithTaskId(Long taskId) {
        ProtocolMessageSendResultReportedEvent event = groupCreationEvent(true);
        return new ProtocolMessageSendResultReportedEvent(
                event.eventId(),
                event.tenantId(),
                event.marketingTaskId(),
                event.targetId(),
                event.attemptId(),
                event.roundNo(),
                event.protocolAccountId(),
                event.groupJid(),
                event.commandId(),
                event.success(),
                event.messageId(),
                event.reasonCode(),
                event.reasonMessage(),
                event.timestamp(),
                event.workerId(),
                taskId,
                event.groupCreationItemId(),
                event.source(),
                event.groupStatus(),
                event.groupStatusReason(),
                event.groupStatusCheckedAt(),
                event.historicalExecutionId(),
                event.historicalMemberId());
    }

    private static MarketingTaskSendAttempt marketingAttempt() {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setId(9001L);
        attempt.setMarketingTaskId(42L);
        attempt.setTargetId(501L);
        attempt.setCommandId("cmd_1");
        return attempt;
    }

    private static MarketingTask marketingTask() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setOwnerUserId(7L);
        return task;
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
        task.setOwnerUserId(7L);
        task.setAccountGroupId(8L);
        return task;
    }
}
