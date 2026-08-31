package com.armada.feed.task.scheduler;

import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.entity.FeedTask;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 动态发布任务轮次调度器。 */
@Component
@Profile("kafka")
public class FeedTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedTaskScheduler.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final FeedTaskMapper taskMapper;
    private final FeedTaskWorker worker;
    private final FeedTaskLifecycleWorker lifecycleWorker;
    private final FeedTaskSchedulerProperties properties;
    private final ExecutorService executor;

    public FeedTaskScheduler(FeedTaskMapper taskMapper,
                             FeedTaskWorker worker,
                             FeedTaskLifecycleWorker lifecycleWorker,
                             FeedTaskSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
        this.lifecycleWorker = lifecycleWorker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getExecutorPoolSize()), runnable -> {
                    Thread thread = new Thread(runnable,
                            "feed-task-worker-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 扫描到期任务并交给自有线程池执行。 */
    @Scheduled(fixedDelayString = "${armada.feed-task.scheduler.scan-fixed-delay-ms:1000}")
    public void scan() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int limit = Math.max(1, properties.getScanLimit());
        for (FeedTask task : taskMapper.selectDueScheduledTasks(now, limit)) {
            startSafely(task);
        }
        for (FeedTask task : taskMapper.selectDueRunningTasks(now, limit)) {
            executor.submit(() -> runSafely(task));
        }
    }

    private void startSafely(FeedTask task) {
        try {
            lifecycleWorker.startDueScheduledTask(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("动态发布任务自动启动失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    private void runSafely(FeedTask task) {
        try {
            worker.runRound(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("动态发布任务轮次执行失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
