package com.armada.platform.kafka.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 协议命令 dispatch 触发器单测。
 *
 * <p>验证 outbox 落库主路径只在事务提交后异步唤醒 dispatcher,避免发送线程读到未提交数据。</p>
 */
class ProtocolCommandDispatchTriggerTest {

    private final List<ProtocolCommandOutbox> rows = List.of(outboxRow("cmd-1"));

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
                new ProtocolCommandDispatcherProperties());
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
                new ProtocolCommandDispatcherProperties());

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
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(dispatcher, executor, properties);

        trigger.dispatchAfterCommit(rows);

        assertThat(executor.tasks).isEmpty();
        verify(dispatcher, never()).dispatchInsertedRows(rows);
    }

    @Test
    void dispatchAfterCommit_dispatchesSynchronouslyWhenExecutorRejectsTask() {
        ProtocolCommandDispatcher dispatcher = dispatcher();
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ProtocolCommandDispatchTrigger trigger = new ProtocolCommandDispatchTrigger(
                dispatcher,
                rejectingExecutor,
                new ProtocolCommandDispatcherProperties());

        assertThatCode(() -> trigger.dispatchAfterCommit(rows)).doesNotThrowAnyException();
        verify(dispatcher).dispatchInsertedRows(rows);
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
