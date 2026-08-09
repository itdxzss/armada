package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PullTaskUnknownResultReconciliationSchedulerTest {

    @Test
    void schedulerUsesDedicatedDaemonThreadAndSurvivesOneRoundFailure() throws Exception {
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                mock(PullTaskUnknownResultReconciliationCoordinator.class);
        CountDownLatch secondRun = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();
        when(coordinator.reconcileIfDue())
                .thenAnswer(invocation -> {
                    thread.set(Thread.currentThread());
                    throw new IllegalStateException("expected");
                })
                .thenAnswer(invocation -> {
                    thread.set(Thread.currentThread());
                    secondRun.countDown();
                    return PullTaskUnknownResultReconciliationStats.empty();
                });
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setFixedDelayMs(5L);
        PullTaskUnknownResultReconciliationScheduler scheduler =
                new PullTaskUnknownResultReconciliationScheduler(coordinator, properties);

        try {
            scheduler.start();

            assertThat(secondRun.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get().getName()).isEqualTo("pull-task-unknown-reconciliation-1");
            assertThat(thread.get().isDaemon()).isTrue();
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void triggerRunsImmediateReconciliation() throws Exception {
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                mock(PullTaskUnknownResultReconciliationCoordinator.class);
        CountDownLatch periodicRun = new CountDownLatch(1);
        CountDownLatch triggeredRun = new CountDownLatch(1);
        when(coordinator.reconcileIfDue()).thenAnswer(invocation -> {
            periodicRun.countDown();
            return PullTaskUnknownResultReconciliationStats.empty();
        });
        when(coordinator.reconcileOnce(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    triggeredRun.countDown();
                    return PullTaskUnknownResultReconciliationStats.empty();
                });
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setFixedDelayMs(60_000L);
        PullTaskUnknownResultReconciliationScheduler scheduler =
                new PullTaskUnknownResultReconciliationScheduler(coordinator, properties);

        try {
            scheduler.start();
            assertThat(periodicRun.await(1, TimeUnit.SECONDS)).isTrue();

            scheduler.trigger();

            assertThat(triggeredRun.await(1, TimeUnit.SECONDS)).isTrue();
            verify(coordinator).reconcileOnce(org.mockito.ArgumentMatchers.anyLong());
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void disabledSchedulerDoesNotRunCoordinator() {
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                mock(PullTaskUnknownResultReconciliationCoordinator.class);
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setEnabled(false);
        PullTaskUnknownResultReconciliationScheduler scheduler =
                new PullTaskUnknownResultReconciliationScheduler(coordinator, properties);

        scheduler.start();
        scheduler.trigger();

        verifyNoInteractions(coordinator);
    }
}
