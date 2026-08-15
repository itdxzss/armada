package com.armada.platform.kafka.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 协议命令 dispatch 触发器单测。
 *
 * <p>验证 outbox 落库主路径只在事务提交后异步唤醒 dispatcher,避免发送线程读到未提交数据。</p>
 */
class ProtocolCommandDispatchTriggerTest {

    private final List<ProtocolCommandOutbox> rows = List.of(outboxRow("cmd-1"));
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchAfterCommit_registersCallbackWhenTransactionSynchronizationActive() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        RecordingExecutor executor = new RecordingExecutor();
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(
                dispatcher,
                executor,
                new ProtocolCommandDispatcherProperties(),
                scheduler);
        TransactionSynchronizationManager.initSynchronization();

        trigger.dispatchAfterCommit(rows);

        assertThat(executor.tasks).isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(executor.tasks).hasSize(1);

        executor.tasks.get(0).run();
        verify(dispatcher).dispatchInsertedRows(rows);
        verify(dispatcher, never()).dispatchPendingNow();
    }

    @Test
    void dispatchAfterCommit_executesImmediatelyWhenNoTransactionSynchronizationActive() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        RecordingExecutor executor = new RecordingExecutor();
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(
                dispatcher,
                executor,
                new ProtocolCommandDispatcherProperties(),
                scheduler);

        trigger.dispatchAfterCommit(rows);

        assertThat(executor.tasks).hasSize(1);
        executor.tasks.get(0).run();
        verify(dispatcher).dispatchInsertedRows(rows);
        verify(dispatcher, never()).dispatchPendingNow();
    }

    @Test
    void dispatchAfterCommit_doesNothingWhenImmediateDispatchDisabled() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        RecordingExecutor executor = new RecordingExecutor();
        ProtocolCommandDispatcherProperties properties = new ProtocolCommandDispatcherProperties();
        properties.setImmediateEnabled(false);
        ProtocolCommandDispatchTrigger trigger =
                new ProtocolCommandDispatchTrigger(dispatcher, executor, properties, scheduler);

        trigger.dispatchAfterCommit(rows);

        assertThat(executor.tasks).isEmpty();
        verify(dispatcher, never()).dispatchInsertedRows(rows);
    }

    @Test
    void dispatchAfterCommit_leavesRowsToTheFallbackScannerWhenExecutorRejectsTask() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(
                dispatcher,
                rejectingExecutor,
                new ProtocolCommandDispatcherProperties(),
                scheduler);

        // 队列满时不能改用当前线程发送：调用方通常是拉群调度线程，
        // 同步发送会把整个调度轮次连同 Kafka 往返一起阻塞。
        // 行已提交为 PENDING，交给周期兜底扫描即可。
        assertThatCode(() -> trigger.dispatchAfterCommit(rows)).doesNotThrowAnyException();
        verify(dispatcher, never()).dispatchInsertedRows(rows);
    }

    @Test
    void dispatchAfterCommit_futureRowsAreScheduledAtNextRetryAt() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        RecordingExecutor executor = new RecordingExecutor();
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        long futureAt = System.currentTimeMillis() + 60_000L;
        ProtocolCommandOutbox futureRow = outboxRow("cmd-future");
        futureRow.setNextRetryAt(futureAt);
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(futureAt)));
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(
                dispatcher,
                executor,
                new ProtocolCommandDispatcherProperties(),
                scheduler);

        trigger.dispatchAfterCommit(List.of(futureRow));

        assertThat(executor.tasks).isEmpty();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(captor.capture(), eq(Instant.ofEpochMilli(futureAt)));
        captor.getValue().run();
        assertThat(executor.tasks).hasSize(1);
        executor.tasks.get(0).run();
        verify(dispatcher).dispatchInsertedRows(List.of(futureRow));
    }

    private static ProtocolCommandDispatcher dispatcher() {
        ProtocolCommandDispatcher dispatcher = mock(ProtocolCommandDispatcher.class);
        when(dispatcher.dispatchInsertedRows(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(ProtocolCommandDispatchResult.empty());
        return dispatcher;
    }

    private static ProtocolCommandOutbox outboxRow(String commandId) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId(commandId);
        row.setRetryCount(0);
        return row;
    }

    private static final class RecordingExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }
}
