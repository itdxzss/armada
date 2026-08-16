package com.armada.account.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 账号 ONLINE 后群元数据恢复任务的有界后台执行器配置。 */
@Configuration
public class AccountStateMetadataRecoveryExecutorConfig {

    /** 后台恢复并发数；限制锁竞争，同时与 Kafka 消费线程彻底隔离。 */
    private static final int POOL_SIZE = 2;

    /** 覆盖一次全量账号上线的等待队列容量。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建账号状态群元数据恢复执行器。
     *
     * @return 支持优雅停机的有界后台执行器
     */
    @Bean(name = "accountStateMetadataRecoveryExecutor")
    public Executor accountStateMetadataRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("account-metadata-recovery-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
