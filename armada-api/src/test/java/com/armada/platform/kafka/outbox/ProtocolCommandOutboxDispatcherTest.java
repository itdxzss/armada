package com.armada.platform.kafka.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 协议命令 Outbox dispatcher 单测。
 *
 * <p>dispatcher 只负责短事务抢占 outbox、事务外发送 Kafka、再回写状态。它不接 Controller,
 * 也不依赖高频定时扫描作为主路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolCommandOutboxDispatcherTest {

    @Mock
    private ProtocolCommandOutboxMapper mapper;

    @Mock
    private ProtocolCommandPublisher publisher;

    private ProtocolCommandDispatcherProperties properties;
    private ProtocolCommandOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new ProtocolCommandDispatcherProperties();
        properties.setPublisherId("publisher-test");
        properties.setBatchSize(10);
        properties.setMaxBatchesPerDrain(1);
        properties.setRetryDelayMs(30_000);
        properties.setMaxRetryCount(2);
        properties.setLockedTimeoutMs(60_000);
        dispatcher = new ProtocolCommandOutboxDispatcher(mapper, publisher, properties);
    }

    @Test
    void dispatchInsertedRows_locksInsertedRowsByCommandIdAndDoesNotScanPending() {
        ProtocolCommandOutbox first = insertedOutboxRow("cmd-201", 201L);
        ProtocolCommandOutbox second = insertedOutboxRow("cmd-202", 202L);
        when(mapper.markLockedByCommandIds(eq(List.of("cmd-201", "cmd-202")), eq("publisher-test"), anyLong()))
                .thenReturn(2);
        when(mapper.markSent(same(first), anyLong())).thenReturn(1);
        when(mapper.markSent(same(second), anyLong())).thenReturn(1);

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
        verify(publisher).publish(first);
        verify(publisher).publish(second);
    }

    @Test
    void dispatchPendingNow_locksSendsAndMarksSentOutsideSelectionTransaction() {
        ProtocolCommandOutbox row = outboxRow(101L, "cmd-101", 0);
        when(mapper.selectDispatchable(eq(ProtocolCommandOutboxStatus.PENDING.code()), anyLong(), eq(10)))
                .thenReturn(List.of(row));
        when(mapper.markLocked(eq(List.of(101L)), eq("publisher-test"), anyLong())).thenReturn(1);
        when(mapper.selectLockedBy(eq(List.of(101L)), eq("publisher-test"), anyLong())).thenReturn(List.of(row));
        when(mapper.markSent(same(row), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.selected()).isEqualTo(1);
        assertThat(result.locked()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        assertThat(result.dead()).isZero();
        verify(publisher).publish(row);
        verify(mapper).markSent(same(row), anyLong());
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
        when(publisher.publish(row)).thenThrow(ProtocolException.unknown("broker down", null));
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
        when(publisher.publish(row)).thenThrow(ProtocolException.unknown("payload invalid", null));
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
        when(publisher.publish(failed)).thenThrow(ProtocolException.unknown("temporary kafka error", null));
        when(mapper.markRetry(same(failed), anyLong(), eq("temporary kafka error"), anyLong())).thenReturn(1);
        when(mapper.markSent(same(sent), anyLong())).thenReturn(1);

        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.dead()).isZero();
        verify(mapper).markRetry(same(failed), anyLong(), eq("temporary kafka error"), anyLong());
        verify(mapper).markSent(same(sent), anyLong());
    }

    @Test
    void recoverExpiredLocks_releasesOnlyRowsOlderThanConfiguredTimeout() {
        when(mapper.releaseExpiredLocks(anyLong(), anyLong(), eq("publisher lock expired"), eq(10)))
                .thenReturn(3);

        int recovered = dispatcher.recoverExpiredLocks();

        assertThat(recovered).isEqualTo(3);
        verify(mapper).releaseExpiredLocks(anyLong(), anyLong(), eq("publisher lock expired"), eq(10));
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
}
