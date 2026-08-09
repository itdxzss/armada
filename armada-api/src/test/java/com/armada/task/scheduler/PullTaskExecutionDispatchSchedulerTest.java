package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PullTaskExecutionDispatchSchedulerTest {

    @Test
    void schedulerUsesOneDaemonThreadAndSurvivesOneRoundFailure() throws Exception {
        PullTaskExecutionDispatchCoordinator coordinator =
                mock(PullTaskExecutionDispatchCoordinator.class);
        CountDownLatch secondRun = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> thread = new AtomicReference<>();
        when(coordinator.dispatchOnce()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("expected");
            }
            secondRun.countDown();
            return PullTaskExecutionDispatchStats.empty();
        });
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setFixedDelayMs(5L);
        PullTaskExecutionDispatchScheduler scheduler =
                new PullTaskExecutionDispatchScheduler(coordinator, properties);

        try {
            scheduler.start();
            assertThat(secondRun.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get().getName()).isEqualTo("pull-task-execution-dispatcher-1");
            assertThat(thread.get().isDaemon()).isTrue();
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void disabledSchedulerDoesNotRunCoordinator() {
        PullTaskExecutionDispatchCoordinator coordinator =
                mock(PullTaskExecutionDispatchCoordinator.class);
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setEnabled(false);
        PullTaskExecutionDispatchScheduler scheduler =
                new PullTaskExecutionDispatchScheduler(coordinator, properties);

        scheduler.start();
        scheduler.trigger();

        verifyNoInteractions(coordinator);
    }

    @Test
    void blockedReconciliationMustNotDelayNextDispatch() throws Exception {
        CountDownLatch reconciliationStarted = new CountDownLatch(1);
        CountDownLatch releaseReconciliation = new CountDownLatch(1);
        CountDownLatch secondDispatch = new CountDownLatch(1);
        AtomicInteger dispatchCalls = new AtomicInteger();
        PullTaskExecutionDispatchCoordinator coordinator =
                mock(PullTaskExecutionDispatchCoordinator.class);
        when(coordinator.dispatchOnce()).thenAnswer(invocation -> {
            if (dispatchCalls.incrementAndGet() == 2) {
                secondDispatch.countDown();
            }
            return PullTaskExecutionDispatchStats.empty();
        });
        PullTaskUnknownResultReconciliationCoordinator reconciliation =
                mock(PullTaskUnknownResultReconciliationCoordinator.class);
        when(reconciliation.reconcileIfDue()).thenAnswer(invocation -> {
            reconciliationStarted.countDown();
            releaseReconciliation.await(2, TimeUnit.SECONDS);
            return null;
        });
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setFixedDelayMs(60_000L);
        PullTaskExecutionDispatchScheduler dispatchScheduler =
                new PullTaskExecutionDispatchScheduler(coordinator, properties);
        PullTaskUnknownResultReconciliationScheduler reconciliationScheduler =
                new PullTaskUnknownResultReconciliationScheduler(reconciliation, properties);

        try {
            reconciliationScheduler.start();
            assertThat(reconciliationStarted.await(1, TimeUnit.SECONDS)).isTrue();
            dispatchScheduler.start();

            dispatchScheduler.trigger();

            assertThat(secondDispatch.await(300, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            releaseReconciliation.countDown();
            dispatchScheduler.destroy();
            reconciliationScheduler.destroy();
        }
    }
}
