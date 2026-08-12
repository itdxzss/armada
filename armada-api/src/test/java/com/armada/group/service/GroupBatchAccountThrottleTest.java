package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 批量刷新账号闸门单测:并发只放大到账号之间，单个账号仍然串行。 */
class GroupBatchAccountThrottleTest {

    private static final long ACCOUNT_ID = 77L;

    @Test
    void oneAccountIsNeverCalledConcurrentlyEvenWhenManyItemsPickIt() throws InterruptedException {
        // 一个租户的群往往集中在少数管理员账号上。批量放开并发后，同一账号会被多条明细同时选中，
        // 对同一个 WhatsApp 连接并发发 IQ 是账号被限流的直接来源，必须在这里挡住。
        GroupBatchAccountThrottle throttle = new GroupBatchAccountThrottle(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                pool.execute(() -> {
                    throttle.run(ACCOUNT_ID, () -> {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        sleep(30L);
                        inFlight.decrementAndGet();
                    });
                    done.countDown();
                });
            }
            assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void differentAccountsRunInParallelSoTheBatchStillFansOut() throws InterruptedException {
        GroupBatchAccountThrottle throttle = new GroupBatchAccountThrottle(1);
        // 两个动作互相等对方进入闸门；若按账号串行，barrier 必然超时。
        CyclicBarrier bothInside = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger arrived = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (long accountId : new long[] {ACCOUNT_ID, ACCOUNT_ID + 1}) {
                pool.execute(() -> {
                    throttle.run(accountId, () -> {
                        await(bothInside);
                        arrived.incrementAndGet();
                    });
                    done.countDown();
                });
            }
            assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(arrived.get()).isEqualTo(2);
    }

    @Test
    void permitIsReleasedWhenTheActionThrowsSoTheAccountDoesNotStayLocked() {
        GroupBatchAccountThrottle throttle = new GroupBatchAccountThrottle(1);

        assertThatThrownBy(() -> throttle.run(ACCOUNT_ID, () -> {
            throw new IllegalStateException("protocol boom");
        })).isInstanceOf(IllegalStateException.class);

        // 许可没归还的话，同账号后续明细会永久卡住，整批任务再也推不动。
        AtomicInteger executed = new AtomicInteger();
        throttle.run(ACCOUNT_ID, executed::incrementAndGet);
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    void unknownAccountIsNotThrottledSoSelectionFailuresStillSettle() {
        GroupBatchAccountThrottle throttle = new GroupBatchAccountThrottle(1);
        AtomicInteger executed = new AtomicInteger();

        throttle.run(null, executed::incrementAndGet);

        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    void configuredPermitsAllowMoreThanOneCallPerAccount() throws InterruptedException {
        GroupBatchAccountThrottle throttle = new GroupBatchAccountThrottle(2);
        CyclicBarrier bothInside = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger arrived = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.execute(() -> {
                    throttle.run(ACCOUNT_ID, () -> {
                        await(bothInside);
                        arrived.incrementAndGet();
                    });
                    done.countDown();
                });
            }
            assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(arrived.get()).isEqualTo(2);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(3L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (BrokenBarrierException | TimeoutException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
