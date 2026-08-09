package com.armada.task.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 普通拉群未知协议结果的独立收敛线程，避免慢查询阻塞拉人批次派发。 */
@Component
@EnableConfigurationProperties(PullTaskExecutionDispatchProperties.class)
public class PullTaskUnknownResultReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskUnknownResultReconciliationScheduler.class);

    private final PullTaskUnknownResultReconciliationCoordinator coordinator;
    private final PullTaskExecutionDispatchProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean signalled = new AtomicBoolean();
    private ScheduledExecutorService executor;

    /** 构造未知结果收敛调度器。 */
    public PullTaskUnknownResultReconciliationScheduler(
            PullTaskUnknownResultReconciliationCoordinator coordinator,
            PullTaskExecutionDispatchProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    /** 容器启动后创建独立守护线程；实际周期仍由 coordinator 的收敛间隔控制。 */
    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pull-task-unknown-reconciliation-1");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runIfDueSafely, 0L, properties.getFixedDelayMs(), TimeUnit.MILLISECONDS);
    }

    /** 在成员查询结果提交后立即触发一轮收敛；重复信号会合并。 */
    public void trigger() {
        ScheduledExecutorService current = executor;
        if (!properties.isEnabled() || current == null || !signalled.compareAndSet(false, true)) {
            return;
        }
        current.execute(() -> {
            signalled.set(false);
            runNowSafely();
        });
    }

    private void runIfDueSafely() {
        runSafely(false);
    }

    private void runNowSafely() {
        runSafely(true);
    }

    private void runSafely(boolean force) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (force) {
                coordinator.reconcileOnce(System.currentTimeMillis());
            } else {
                coordinator.reconcileIfDue();
            }
        } catch (RuntimeException ex) {
            log.error("普通拉群未知结果收敛单轮失败", ex);
        } finally {
            running.set(false);
        }
    }

    /** 容器销毁时停止专用线程。 */
    @PreDestroy
    public void destroy() {
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
