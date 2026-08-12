package com.armada.group.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 群组列表批量刷新任务的有界后台执行器配置。 */
@Configuration
public class GroupBatchTaskExecutorConfig {

    /** 常驻工作线程数。 */
    private static final int CORE_POOL_SIZE = 1;

    /** 高峰最大工作线程数。 */
    private static final int MAX_POOL_SIZE = 2;

    /** 等待队列最大任务数。 */
    private static final int QUEUE_CAPACITY = 50;

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
}
