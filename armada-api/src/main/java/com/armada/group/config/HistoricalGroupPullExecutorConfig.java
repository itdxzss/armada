package com.armada.group.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 历史群一次性拉人有界后台执行器配置。 */
@Configuration
public class HistoricalGroupPullExecutorConfig {

    /** 常驻工作线程数。 */
    private static final int CORE_POOL_SIZE = 1;

    /** 高峰最大工作线程数。 */
    private static final int MAX_POOL_SIZE = 2;

    /** 等待队列最大任务数。 */
    private static final int QUEUE_CAPACITY = 50;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建历史群拉人有界执行器。
     *
     * @return 支持优雅停机的 Spring 线程池
     */
    @Bean(name = "historicalGroupPullExecutor")
    public Executor historicalGroupPullExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("historical-group-pull-");
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
