package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 营销轮次调度器。
 *
 * <p>调度线程只扫描到期任务并投递到固定线程池,真正的抢占、写 attempt 和写 outbox
 * 都在 {@link MarketingRoundWorker} 的事务里完成。</p>
 */
@Component
@Profile("kafka")
public class MarketingRoundScheduler {
    private static final Logger log = LoggerFactory.getLogger(MarketingRoundScheduler.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final MarketingTaskMapper taskMapper;
    private final MarketingRoundWorker worker;
    private final MarketingTaskLifecycleWorker lifecycleWorker;
    private final MarketingRoundSchedulerProperties properties;
    private final ExecutorService executor;

    public MarketingRoundScheduler(MarketingTaskMapper taskMapper,
                                   MarketingRoundWorker worker,
                                   MarketingTaskLifecycleWorker lifecycleWorker,
                                   MarketingRoundSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
        this.lifecycleWorker = lifecycleWorker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getExecutorPoolSize()), runnable -> {
            Thread thread = new Thread(runnable,
                    "marketing-round-worker-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 按配置周期扫描已到 {@code next_round_at} 的发送中任务。 */
    @Scheduled(fixedDelayString = "${armada.marketing.round-scheduler.scan-fixed-delay-ms:1000}")
    public void scanDueTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int limit = Math.max(1, properties.getScanLimit());
        List<MarketingTask> expiredTasks = taskMapper.selectExpiredRunnableTasks(now, limit);
        for (MarketingTask task : expiredTasks) {
            endSafely(task);
        }
        List<MarketingTask> waitingTasks = taskMapper.selectDueWaitingTasks(now, limit);
        for (MarketingTask task : waitingTasks) {
            startSafely(task);
        }
        List<MarketingTask> tasks = taskMapper.selectDueSendingTasks(now, limit);
        for (MarketingTask task : tasks) {
            executor.execute(() -> runSafely(task));
        }
    }

    private void endSafely(MarketingTask task) {
        try {
            lifecycleWorker.endExpiredTask(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("营销任务自动结束失败 tenantId={} taskId={}", task.getTenantId(), task.getId(), ex);
        }
    }

    private void startSafely(MarketingTask task) {
        try {
            lifecycleWorker.startDueWaitingTask(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("营销任务自动启动失败 tenantId={} taskId={}", task.getTenantId(), task.getId(), ex);
        }
    }

    /** 单个任务失败只记录日志,不影响同批其他任务继续提交到线程池执行。 */
    private void runSafely(MarketingTask task) {
        try {
            worker.runRound(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("营销任务轮次执行失败 tenantId={} taskId={}", task.getTenantId(), task.getId(), ex);
        }
    }

    /** 应用关闭时停止轮次执行线程池,避免测试和部署退出时悬挂后台线程。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
