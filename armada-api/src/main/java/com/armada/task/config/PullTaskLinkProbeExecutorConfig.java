package com.armada.task.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 普通群链接创建页公开邀请页预检的有界并发执行器配置。 */
@Configuration
public class PullTaskLinkProbeExecutorConfig {

    /**
     * 并发抓取线程数。
     *
     * <p>单条抓取最坏 3 秒（连接 2 秒 + 请求 3 秒超时），单次上限 200 条链接，
     * 16 并发下最坏约 38 秒。再往上对 {@code chat.whatsapp.com} 有被限流风险。</p>
     */
    private static final int POOL_SIZE = 16;

    /** 等待队列容量；单次上限 200 条，留一倍冗余应对并发请求。 */
    private static final int QUEUE_CAPACITY = 400;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建邀请页预检有界执行器。
     *
     * @return 支持优雅停机的 Spring 线程池
     */
    @Bean(name = "pullTaskLinkProbeExecutor")
    public Executor pullTaskLinkProbeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pull-task-link-probe-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
