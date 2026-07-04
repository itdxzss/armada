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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(MarketingRoundSchedulerProperties.class)
public class MarketingRoundScheduler {
    private static final Logger log = LoggerFactory.getLogger(MarketingRoundScheduler.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final MarketingTaskMapper taskMapper;
    private final MarketingRoundWorker worker;
    private final MarketingRoundSchedulerProperties properties;
    private final ExecutorService executor;

    public MarketingRoundScheduler(MarketingTaskMapper taskMapper,
                                   MarketingRoundWorker worker,
                                   MarketingRoundSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
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
        List<MarketingTask> tasks = taskMapper.selectDueSendingTasks(System.currentTimeMillis(),
                Math.max(1, properties.getScanLimit()));
        for (MarketingTask task : tasks) {
            executor.execute(() -> runSafely(task));
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
