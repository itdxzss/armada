package com.armada.feed.task.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 动态发布结果重试与终态计数单测。 */
class FeedTaskSendResultSinkTest {

    private final FeedTaskMapper taskMapper = Mockito.mock(FeedTaskMapper.class);
    private final FeedTaskAccountMapper accountMapper = Mockito.mock(FeedTaskAccountMapper.class);
    private final FeedTaskSendResultSink sink = new FeedTaskSendResultSink(taskMapper, accountMapper);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void allowsConfiguredNumberOfRetriesAfterInitialAttempt() {
        FeedTaskAccount row = sendingAccount(3, 3);
        when(accountMapper.selectById(7001L)).thenReturn(row);
        when(accountMapper.markRetrying(7001L, "TEMP", "temporary", 0L)).thenReturn(1);

        sink.handleSendResultReported(failedEvent());

        verify(accountMapper).markRetrying(
                org.mockito.ArgumentMatchers.eq(7001L),
                org.mockito.ArgumentMatchers.eq("TEMP"),
                org.mockito.ArgumentMatchers.eq("temporary"),
                org.mockito.ArgumentMatchers.anyLong());
        verify(accountMapper, never()).markFailed(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void marksFinalFailureAfterRetryBudgetIsExhausted() {
        FeedTaskAccount row = sendingAccount(4, 3);
        when(accountMapper.selectById(7001L)).thenReturn(row);
        when(accountMapper.markFailed(
                org.mockito.ArgumentMatchers.eq(7001L),
                org.mockito.ArgumentMatchers.eq("TEMP"),
                org.mockito.ArgumentMatchers.eq("temporary"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        sink.handleSendResultReported(failedEvent());

        verify(accountMapper).markFailed(
                org.mockito.ArgumentMatchers.eq(7001L),
                org.mockito.ArgumentMatchers.eq("TEMP"),
                org.mockito.ArgumentMatchers.eq("temporary"),
                org.mockito.ArgumentMatchers.anyLong());
        verify(taskMapper).incrementFailedAccountNum(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.anyLong());
        verify(accountMapper, never()).markRetrying(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static FeedTaskAccount sendingAccount(int retryNum, int retryMax) {
        FeedTaskAccount row = new FeedTaskAccount();
        row.setId(7001L);
        row.setTaskId(42L);
        row.setSendStatus("sending");
        row.setRetryNum(retryNum);
        row.setRetryMax(retryMax);
        return row;
    }

    private static ProtocolMessageSendResultReportedEvent failedEvent() {
        return new ProtocolMessageSendResultReportedEvent(
                "evt-feed", 5L, null, null, null, 3L,
                "acc-web", null, "cmd-feed", false, null, "TEMP", "temporary",
                1_000L, "worker-a", null, null, "feed_task", null, null, null,
                null, null, null, null, null, "status@broadcast", "STATUS",
                null, null, 42L, 7001L, "FAILED", true);
    }
}
