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

/**
 * 独立单线程进群到期调度器。
 *
 * <p>线程只执行有界数据库扫描和 outbox 入队，不进行 HTTP 调用，也不等待 Kafka 或 WhatsApp 结果。
 * 单线程只约束“扫描轮次”不重叠；不同账号的到期明细仍可在同一轮写入 outbox，由 Kafka 并行处理。</p>
 */
@Component
@EnableConfigurationProperties(JoinTaskDispatchProperties.class)
public class JoinTaskDispatchScheduler {

    /** 记录单轮调度异常，避免异常终止后续周期。 */
    private static final Logger log = LoggerFactory.getLogger(JoinTaskDispatchScheduler.class);

    /** 负责执行一轮跨租户扫描和分租户派发。 */
    private final JoinTaskDispatchCoordinator coordinator;

    /** 调度启用、周期与批量配置。 */
    private final JoinTaskDispatchProperties properties;

    /** 防止容器生命周期回调重复创建执行器。 */
    private final AtomicBoolean started = new AtomicBoolean();

    /** 防止测试或未来手工触发入口与周期任务重叠执行。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /** 进群专用单线程执行器；关闭组件时必须释放。 */
    private ScheduledExecutorService executor;

    /**
     * 创建进群到期调度器。
     *
     * @param coordinator 单轮调度协调器
     * @param properties 调度配置
     */
    public JoinTaskDispatchScheduler(JoinTaskDispatchCoordinator coordinator,
                                     JoinTaskDispatchProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    /**
     * 容器初始化后按配置创建独立守护线程并启动固定延迟轮询。
     *
     * <p>禁用时不创建线程；重复调用也只会启动一次。</p>
     */
    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "join-task-dispatcher-1");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnceSafely, 0L, properties.getFixedDelayMs(), TimeUnit.MILLISECONDS);
    }

    /** 执行一轮并隔离异常，保证单次数据库或业务失败不会取消后续轮询。 */
    private void runOnceSafely() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            coordinator.dispatchOnce();
        } catch (Exception ex) {
            log.error("进群到期调度单轮失败", ex);
        } finally {
            running.set(false);
        }
    }

    /** 容器销毁时立即停止进群专用执行器，避免应用退出后继续扫描。 */
    @PreDestroy
    public void destroy() {
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
