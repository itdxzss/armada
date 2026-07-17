package com.armada.task.scheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JoinTaskDispatchSchedulerTest {

    @Test
    void start_usesOneNamedDaemonThreadAndSurvivesOneCoordinatorFailure() throws Exception {
        JoinTaskDispatchCoordinator coordinator = mock(JoinTaskDispatchCoordinator.class);
        CountDownLatch secondRun = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> thread = new AtomicReference<>();
        when(coordinator.dispatchOnce()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("expected test failure");
            }
            secondRun.countDown();
            return JoinTaskDispatchStats.empty();
        });
        JoinTaskDispatchProperties properties = new JoinTaskDispatchProperties();
        properties.setEnabled(true);
        properties.setFixedDelayMs(5);
        JoinTaskDispatchScheduler scheduler = new JoinTaskDispatchScheduler(coordinator, properties);

        try {
            scheduler.start();
            assertThat(secondRun.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get().getName()).isEqualTo("join-task-dispatcher-1");
            assertThat(thread.get().isDaemon()).isTrue();
        } finally {
            scheduler.destroy();
        }
    }
}
