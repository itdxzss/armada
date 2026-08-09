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

/** 全局共享的普通群链接执行调度线程；不会为每个任务创建独立线程。 */
@Component
@EnableConfigurationProperties(PullTaskExecutionDispatchProperties.class)
public class PullTaskExecutionDispatchScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskExecutionDispatchScheduler.class);

    private final PullTaskExecutionDispatchCoordinator coordinator;
    private final PullTaskExecutionDispatchProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean signalled = new AtomicBoolean();
    private ScheduledExecutorService executor;

    /**
     * @param coordinator 单轮调度协调器
     * @param properties  调度配置
     */
    public PullTaskExecutionDispatchScheduler(
            PullTaskExecutionDispatchCoordinator coordinator,
            PullTaskExecutionDispatchProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    /** 容器启动后创建一个守护线程，以固定延迟执行恢复扫描。 */
    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pull-task-execution-dispatcher-1");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runOnceSafely, 0L, properties.getFixedDelayMs(), TimeUnit.MILLISECONDS);
    }

    /** 在任务启动事务提交后唤醒共享线程；重复信号会合并。 */
    public void trigger() {
        ScheduledExecutorService current = executor;
        if (!properties.isEnabled() || current == null || !signalled.compareAndSet(false, true)) {
            return;
        }
        current.execute(() -> {
            signalled.set(false);
            runOnceSafely();
        });
    }

    private void runOnceSafely() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            coordinator.dispatchOnce();
        } catch (RuntimeException ex) {
            log.error("普通拉群执行调度单轮失败", ex);
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
