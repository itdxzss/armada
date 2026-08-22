package com.armada.account.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 账号上线后邀请码延期任务恢复线程池配置。 */
@Configuration
public class AccountStateInviteRecoveryExecutorConfig {

    /** 后台恢复并发数；限制锁竞争，同时与 Kafka 消费线程彻底隔离。 */
    private static final int POOL_SIZE = 2;

    /** 覆盖一次全量账号上线的等待队列容量。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建不阻塞账号状态 Kafka 消费线程的邀请码恢复执行器。
     *
     * @return 群邀请码恢复后台执行器
     */
    @Bean(name = "accountStateInviteRecoveryExecutor")
    public Executor accountStateInviteRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("account-invite-recovery-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
