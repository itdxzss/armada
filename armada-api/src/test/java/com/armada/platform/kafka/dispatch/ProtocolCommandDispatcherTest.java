package com.armada.platform.kafka.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.kafka.producer.ProtocolCommandPublisher;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishOutcome;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * 协议命令 Outbox dispatcher 单测。
 *
 * <p>dispatcher 只负责短事务抢占 outbox、事务外发送 Kafka、再回写状态。它不接 Controller,
 * 也不依赖高频定时扫描作为主路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolCommandDispatcherTest {

    @Mock
    private ProtocolCommandOutboxMapper mapper;

    @Mock
    private ProtocolCommandPublisher publisher;

    private ProtocolCommandDispatcherProperties properties;
    private ProtocolCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new ProtocolCommandDispatcherProperties();
        properties.setPublisherId("publisher-test");
        properties.setBatchSize(10);
        properties.setMaxBatchesPerDrain(1);
        properties.setRetryDelayMs(30_000);
        properties.setMaxRetryCount(2);
        properties.setLockedTimeoutMs(60_000);
        dispatcher = new ProtocolCommandDispatcher(mapper, publisher, properties);
        lenient().when(publisher.maximumWindowSendDurationMs()).thenReturn(70_000L);
        lenient().when(mapper.markDispatching(anyList(), anyLong()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    }

    @Test
    void dispatchInsertedRows_locksInsertedRowsByCommandIdAndDoesNotScanPending() {
        ProtocolCommandOutbox first = insertedOutboxRow("cmd-201", 201L);
        ProtocolCommandOutbox second = insertedOutboxRow("cmd-202", 202L);
        when(mapper.markLockedByCommandIds(eq(List.of("cmd-201", "cmd-202")), eq("publisher-test"), anyLong()))
                .thenReturn(2);
        publishWindow(List.of(first, second), List.of(success(first), success(second)));
        when(mapper.markSentBatch(eq(List.of(first, second)), anyLong())).thenReturn(2);

        ProtocolCommandDispatchResult result = dispatcher.dispatchInsertedRows(List.of(first, second));

        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.locked()).isEqualTo(2);
        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.retried()).isZero();
        assertThat(result.dead()).isZero();
        assertThat(first.getLockedBy()).isEqualTo("publisher-test");
        assertThat(first.getLockedAt()).isNotNull();
        assertThat(second.getLockedBy()).isEqualTo("publisher-test");
        assertThat(second.getLockedAt()).isEqualTo(first.getLockedAt());
        verify(mapper, never()).selectDispatchable(anyInt(), anyLong(), anyInt());
        verify(mapper, never()).selectLockedByCommandIds(anyList(), eq("publisher-test"), anyLong());
        verify(publisher).publishBatchByWindow(eq(List.of(first, second)), any(), any());
        verify(mapper).markSentBatch(eq(List.of(first, second)), anyLong());
    }

    @Test
    void dispatchInsertedRows_marksEachCompletedWindowSeparately() {
        ProtocolCommandOutbox first = insertedOutboxRow("cmd-301", 301L);
        ProtocolCommandOutbox second = insertedOutboxRow("cmd-302", 302L);
        ProtocolCommandOutbox third = insertedOutboxRow("cmd-303", 303L);
        List<ProtocolCommandOutbox> rows = List.of(first, second, third);
        when(mapper.markLockedByCommandIds(
                eq(List.of("cmd-301", "cmd-302", "cmd-303")), eq("publisher-test"), anyLong()))
                .thenReturn(3);
        doAnswer(invocation -> {
            Function<List<ProtocolCommandOutbox>, List<ProtocolCommandOutbox>> selector =
                    invocation.getArgument(1);
            Consumer<List<ProtocolCommandPublishOutcome>> consumer = invocation.getArgument(2);
            assertThat(selector.apply(List.of(first, second))).containsExactly(first, second);
            consumer.accept(List.of(success(first), success(second)));
            assertThat(selector.apply(List.of(third))).containsExactly(third);
            consumer.accept(List.of(success(third)));
            return null;
        }).when(publisher).publishBatchByWindow(eq(rows), any(), any());
        when(mapper.markSentBatch(eq(List.of(first, second)), anyLong())).thenReturn(2);
        when(mapper.markSentBatch(eq(List.of(third)), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchInsertedRows(rows);

        assertThat(result.sent()).isEqualTo(3);
        verify(mapper).markSentBatch(eq(List.of(first, second)), anyLong());
        verify(mapper).markSentBatch(eq(List.of(third)), anyLong());
    }

    @Test
    void dispatchInsertedRows_logsElapsedMsInSummary() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProtocolCommandDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolCommandOutbox row = insertedOutboxRow("cmd-203", 203L);
            when(mapper.markLockedByCommandIds(eq(List.of("cmd-203")), eq("publisher-test"), anyLong()))
                    .thenReturn(1);
            publishWindow(List.of(row), List.of(success(row)));
            when(mapper.markSentBatch(eq(List.of(row)), anyLong())).thenReturn(1);

            dispatcher.dispatchInsertedRows(List.of(row));

            assertThat(appender.list)
                    .anyMatch(event -> event.getFormattedMessage().contains("协议命令 outbox dispatch 完成")
                            && event.getFormattedMessage().contains("elapsedMs="));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void dispatchInsertedRows_taskEndWinsBeforeSendCommit_doesNotPublishCanceledLock() {
        ProtocolCommandOutbox row = insertedOutboxRow("cmd-canceled", 204L);
        when(mapper.markLockedByCommandIds(eq(List.of("cmd-canceled")), eq("publisher-test"), anyLong()))
                .thenReturn(1);
        when(mapper.markDispatching(eq(List.of(row)), anyLong())).thenReturn(0);
        publishWindow(List.of(row), List.of());

        ProtocolCommandDispatchResult result = dispatcher.dispatchInsertedRows(List.of(row));

        assertThat(result.selected()).isEqualTo(1);
        assertThat(result.locked()).isZero();
        assertThat(result.sent()).isZero();
        verify(publisher).publishBatchByWindow(eq(List.of(row)), any(), any());
        verify(mapper, never()).markSentBatch(anyList(), anyLong());
    }

    @Test
    void dispatchInsertedRows_partialSendCommit_publishesOnlyRowsThatWonCas() {
        ProtocolCommandOutbox canceled = insertedOutboxRow("cmd-canceled-partial", 205L);
        ProtocolCommandOutbox dispatching = insertedOutboxRow("cmd-dispatching-partial", 206L);
        List<ProtocolCommandOutbox> rows = List.of(canceled, dispatching);
        when(mapper.markLockedByCommandIds(
                eq(List.of("cmd-canceled-partial", "cmd-dispatching-partial")),
                eq("publisher-test"), anyLong())).thenReturn(2);
        when(mapper.markDispatching(eq(rows), anyLong())).thenReturn(1);
        when(mapper.selectDispatchingByCommandIds(
                eq(List.of("cmd-canceled-partial", "cmd-dispatching-partial")),
                eq("publisher-test"), anyLong())).thenReturn(List.of(dispatching));
        publishWindow(rows, List.of(success(dispatching)));
        when(mapper.markSentBatch(eq(List.of(dispatching)), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchInsertedRows(rows);

        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.locked()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        verify(publisher).publishBatchByWindow(eq(rows), any(), any());
    }

    @Test
    void dispatchPendingNow_locksSendsAndMarksSentOutsideSelectionTransaction() {
        ProtocolCommandOutbox row = outboxRow(101L, "cmd-101", 0);
        when(mapper.selectDispatchable(eq(ProtocolCommandOutboxStatus.PENDING.code()), anyLong(), eq(10)))
                .thenReturn(List.of(row));
        when(mapper.markLocked(eq(List.of(101L)), eq("publisher-test"), anyLong())).thenReturn(1);
        when(mapper.selectLockedBy(eq(List.of(101L)), eq("publisher-test"), anyLong())).thenReturn(List.of(row));
        publishWindow(List.of(row), List.of(success(row)));
        when(mapper.markSentBatch(eq(List.of(row)), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.selected()).isEqualTo(1);
        assertThat(result.locked()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        assertThat(result.dead()).isZero();
        verify(publisher).publishBatchByWindow(eq(List.of(row)), any(), any());
        verify(mapper).markSentBatch(eq(List.of(row)), anyLong());
        verify(mapper, never()).markRetry(same(row), anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
        verify(mapper, never()).markDead(same(row), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void dispatchPendingNow_sendFailsBelowRetryLimitReleasesToPending() {
        ProtocolCommandOutbox row = outboxRow(102L, "cmd-102", 0);
        when(mapper.selectDispatchable(eq(ProtocolCommandOutboxStatus.PENDING.code()), anyLong(), eq(10)))
                .thenReturn(List.of(row));
        when(mapper.markLocked(eq(List.of(102L)), eq("publisher-test"), anyLong())).thenReturn(1);
        when(mapper.selectLockedBy(eq(List.of(102L)), eq("publisher-test"), anyLong())).thenReturn(List.of(row));
        publishWindow(List.of(row),
                List.of(failure(row, ProtocolException.unknown("broker down", null))));
        when(mapper.markRetry(same(row), anyLong(), eq("broker down"), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.sent()).isZero();
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.dead()).isZero();
        verify(mapper).markRetry(same(row), anyLong(), eq("broker down"), anyLong());
        verify(mapper, never()).markDead(same(row), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void dispatchPendingNow_sendFailsAtRetryLimitMarksDead() {
        ProtocolCommandOutbox row = outboxRow(103L, "cmd-103", 1);
        when(mapper.selectDispatchable(eq(ProtocolCommandOutboxStatus.PENDING.code()), anyLong(), eq(10)))
                .thenReturn(List.of(row));
        when(mapper.markLocked(eq(List.of(103L)), eq("publisher-test"), anyLong())).thenReturn(1);
        when(mapper.selectLockedBy(eq(List.of(103L)), eq("publisher-test"), anyLong())).thenReturn(List.of(row));
        publishWindow(List.of(row),
                List.of(failure(row, ProtocolException.unknown("payload invalid", null))));
        when(mapper.markDead(same(row), eq("payload invalid"), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.sent()).isZero();
        assertThat(result.retried()).isZero();
        assertThat(result.dead()).isEqualTo(1);
        verify(mapper).markDead(same(row), eq("payload invalid"), anyLong());
        verify(mapper, never()).markRetry(same(row), anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void dispatchPendingNow_oneFailedRowDoesNotStopOtherLockedRows() {
        ProtocolCommandOutbox failed = outboxRow(104L, "cmd-104", 0);
        ProtocolCommandOutbox sent = outboxRow(105L, "cmd-105", 0);
        when(mapper.selectDispatchable(eq(ProtocolCommandOutboxStatus.PENDING.code()), anyLong(), eq(10)))
                .thenReturn(List.of(failed, sent));
        when(mapper.markLocked(eq(List.of(104L, 105L)), eq("publisher-test"), anyLong())).thenReturn(2);
        when(mapper.selectLockedBy(eq(List.of(104L, 105L)), eq("publisher-test"), anyLong()))
                .thenReturn(List.of(failed, sent));
        publishWindow(List.of(failed, sent), List.of(
                failure(failed, ProtocolException.unknown("temporary kafka error", null)),
                success(sent)));
        when(mapper.markRetry(same(failed), anyLong(), eq("temporary kafka error"), anyLong())).thenReturn(1);
        when(mapper.markSentBatch(eq(List.of(sent)), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.dead()).isZero();
        verify(mapper).markRetry(same(failed), anyLong(), eq("temporary kafka error"), anyLong());
        verify(mapper).markSentBatch(eq(List.of(sent)), anyLong());
    }

    @Test
    void recoverExpiredLocks_releasesOnlyRowsOlderThanConfiguredTimeout() {
        when(mapper.releaseExpiredLocks(anyLong(), anyLong(), eq("publisher lock expired"), eq(10)))
                .thenReturn(3);
        when(mapper.markExpiredDispatchingDead(
                anyLong(), anyLong(), eq("publisher dispatch outcome unknown"), eq(10)))
                .thenReturn(2);

        int recovered = dispatcher.recoverExpiredLocks();

        assertThat(recovered).isEqualTo(5);
        verify(mapper).releaseExpiredLocks(anyLong(), anyLong(), eq("publisher lock expired"), eq(10));
        verify(mapper).markExpiredDispatchingDead(
                anyLong(), anyLong(), eq("publisher dispatch outcome unknown"), eq(10));
    }

    @Test
    void recoverExpiredLocks_keepsDispatchingAliveLongerThanMaximumWindowDuration() {
        when(publisher.maximumWindowSendDurationMs()).thenReturn(180_000L);

        dispatcher.recoverExpiredLocks();

        ArgumentCaptor<Long> lockedBefore = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> dispatchingBefore = ArgumentCaptor.forClass(Long.class);
        verify(mapper).releaseExpiredLocks(
                lockedBefore.capture(), anyLong(), eq("publisher lock expired"), eq(10));
        verify(mapper).markExpiredDispatchingDead(
                dispatchingBefore.capture(), anyLong(), eq("publisher dispatch outcome unknown"), eq(10));
        assertThat(lockedBefore.getValue() - dispatchingBefore.getValue()).isEqualTo(121_000L);
        verify(mapper).markExpiredCancelRequestedCanceled(
                eq(dispatchingBefore.getValue()), anyLong(),
                eq("publisher dispatch canceled after task end"), eq(10));
    }

    private static ProtocolCommandOutbox outboxRow(Long id, String commandId, int retryCount) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setId(id);
        row.setCommandId(commandId);
        row.setBatchId("batch-1");
        row.setCommandType("account.online.requested");
        row.setAggregateType("ACCOUNT");
        row.setAggregateId(id);
        row.setKafkaTopic("protocol.account.commands.v1");
        row.setKafkaKey("acc_" + id);
        row.setProtocolAccountId("acc_" + id);
        row.setPayloadJson("{\"accountId\":" + id + "}");
        row.setRetryCount(retryCount);
        return row;
    }

    private static ProtocolCommandOutbox insertedOutboxRow(String commandId, Long accountId) {
        ProtocolCommandOutbox row = outboxRow(null, commandId, 0);
        row.setAggregateId(accountId);
        row.setKafkaKey("acc_" + accountId);
        row.setProtocolAccountId("acc_" + accountId);
        row.setPayloadJson("{\"accountId\":" + accountId + "}");
        return row;
    }

    private static ProtocolCommandPublishOutcome success(ProtocolCommandOutbox row) {
        return ProtocolCommandPublishOutcome.success(row, null);
    }

    private static ProtocolCommandPublishOutcome failure(ProtocolCommandOutbox row, RuntimeException error) {
        return ProtocolCommandPublishOutcome.failure(row, error);
    }

    private void publishWindow(List<ProtocolCommandOutbox> rows,
                               List<ProtocolCommandPublishOutcome> outcomes) {
        doAnswer(invocation -> {
            Function<List<ProtocolCommandOutbox>, List<ProtocolCommandOutbox>> selector =
                    invocation.getArgument(1);
            Consumer<List<ProtocolCommandPublishOutcome>> consumer = invocation.getArgument(2);
            List<ProtocolCommandOutbox> dispatchingRows = selector.apply(rows);
            List<ProtocolCommandPublishOutcome> selectedOutcomes = outcomes.stream()
                    .filter(outcome -> dispatchingRows.contains(outcome.row()))
                    .toList();
            if (!selectedOutcomes.isEmpty()) {
                consumer.accept(selectedOutcomes);
            }
            return null;
        }).when(publisher).publishBatchByWindow(eq(rows), any(), any());
    }
}
