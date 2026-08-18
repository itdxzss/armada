package com.armada.group.config;

import com.armada.group.scheduler.GroupBatchTaskExecutors;
import com.armada.group.scheduler.GroupBatchTaskJobProperties;
import com.armada.group.service.GroupSnapshotProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 群组列表批量刷新任务的有界后台执行器配置。
 *
 * <p>分两层:任务层只负责扫描分派，明细层负责写 Outbox 与状态关联。两层分开才能既保证
 * 任务不互相堵塞，又能按明细并发放大吞吐。</p>
 */
@Configuration
@EnableConfigurationProperties({GroupBatchTaskJobProperties.class, GroupSnapshotProperties.class})
public class GroupBatchTaskExecutorConfig {

    /** 任务层常驻工作线程数。 */
    private static final int CORE_POOL_SIZE = 1;

    /** 任务层高峰最大工作线程数。 */
    private static final int MAX_POOL_SIZE = 2;

    /** 任务层等待队列最大任务数。 */
    private static final int QUEUE_CAPACITY = 50;

    /** 明细层等待队列最大任务数。 */
    private static final int ITEM_QUEUE_CAPACITY = 50;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建批量任务有界执行器。
     *
     * <p>应用内 @Scheduled 共用 Spring 默认单线程调度器。批量执行要发同步协议调用，
     * 必须挪到自有线程池，否则一轮批量会把群详情同步等其余定时任务全部堵住。</p>
     *
     * @return 支持优雅停机的 Spring 线程池
     */
    @Bean(name = "groupBatchTaskExecutor")
    public Executor groupBatchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("group-batch-task-");
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * 创建批量明细有界执行器。
     *
     * <p>明细逐条写本地事务与 Outbox，按
     * {@code armada.group-batch-task.item-concurrency}(默认 6)限制数据库瞬时并发；
     * 协议端压力由 Kafka 消费并发与账号门禁控制。</p>
     *
     * <p>并发数走 {@code @Value} 而不是上面那个 record:并发是这里唯一必须确保默认值生效的量。</p>
     *
     * @param itemConcurrency 明细并发上限
     * @return 支持优雅停机的 Spring 线程池
     */
    @Bean(name = "groupBatchItemExecutor")
    public Executor groupBatchItemExecutor(
            @Value("${armada.group-batch-task.item-concurrency:6}") int itemConcurrency) {
        int concurrency = Math.max(1, itemConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("group-batch-item-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(ITEM_QUEUE_CAPACITY);
        // 队列满时由投递线程自己跑完这一条:不丢明细，也不让队列无限堆积。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * 组合两层执行器。
     *
     * <p>显式装配而不是让记录类自己按限定符注入，避免两个同类型 Executor 造成歧义。</p>
     *
     * @param taskExecutor 任务层执行器
     * @param itemExecutor 明细层执行器
     * @return 执行器组合
     */
    @Bean
    public GroupBatchTaskExecutors groupBatchTaskExecutors(
            @Qualifier("groupBatchTaskExecutor") Executor taskExecutor,
            @Qualifier("groupBatchItemExecutor") Executor itemExecutor) {
        return new GroupBatchTaskExecutors(taskExecutor, itemExecutor);
    }
}
