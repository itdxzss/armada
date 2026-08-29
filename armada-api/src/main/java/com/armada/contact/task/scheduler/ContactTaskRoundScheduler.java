package com.armada.contact.task.scheduler;

import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通讯录营销轮次调度器。
 *
 * <p>调度线程只扫描到期任务并投递到固定线程池，真正的抢占、抢批和写 outbox
 * 都在 {@link ContactTaskRoundWorker} 的事务里完成。</p>
 */
@Component
@Profile("kafka")
public class ContactTaskRoundScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskRoundScheduler.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ContactFriendTaskMapper taskMapper;
    private final ContactTaskRoundWorker worker;
    private final ContactTaskLifecycleWorker lifecycleWorker;
    private final ContactTaskSchedulerProperties properties;
    private final ExecutorService executor;

    /**
     * 创建调度器并按配置建立轮次执行线程池。
     *
     * @param taskMapper 任务主表数据访问
     * @param worker 轮次执行器
     * @param lifecycleWorker 生命周期推进器
     * @param properties 调度参数
     */
    public ContactTaskRoundScheduler(ContactFriendTaskMapper taskMapper,
                                     ContactTaskRoundWorker worker,
                                     ContactTaskLifecycleWorker lifecycleWorker,
                                     ContactTaskSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
        this.lifecycleWorker = lifecycleWorker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getExecutorPoolSize()), runnable -> {
                    Thread thread = new Thread(runnable,
                            "contact-task-round-worker-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 按配置周期扫描到点待启动与到期进行中的任务。 */
    @Scheduled(fixedDelayString = "${armada.contact.round-scheduler.scan-fixed-delay-ms:1000}")
    public void scanDueTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int limit = Math.max(1, properties.getScanLimit());
        for (ContactFriendTask task : taskMapper.selectDueScheduledTasks(now, limit)) {
            startSafely(task);
        }
        for (ContactFriendTask task : taskMapper.selectDueRunningTasks(now, limit)) {
            executor.execute(() -> runSafely(task));
        }
    }

    private void startSafely(ContactFriendTask task) {
        try {
            lifecycleWorker.startDueScheduledTask(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("通讯录任务自动启动失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    /** 单任务失败只记日志，不影响同批其他任务继续提交到线程池执行。 */
    private void runSafely(ContactFriendTask task) {
        try {
            worker.runRound(task.getTenantId(), task.getId());
        } catch (RuntimeException ex) {
            log.warn("通讯录任务轮次执行失败 tenantId={} taskId={}",
                    task.getTenantId(), task.getId(), ex);
        }
    }

    /** 应用关闭时停止线程池，避免测试和部署退出时悬挂后台线程。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
