package com.armada.group.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 群资料上报后主动取邀请码的有界后台执行器配置。 */
@Configuration
public class GroupInviteFetchExecutorConfig {

    /** 并发取邀请码的线程数;每次取要向 WhatsApp 发一次请求,不宜过大以免触发限流。 */
    private static final int POOL_SIZE = 4;

    /** 等待队列容量;一次批量建群会瞬间涌入大量建档事件。 */
    private static final int QUEUE_CAPACITY = 2048;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建群邀请码主动抓取执行器。
     *
     * <p>与 Kafka 消费线程彻底隔离:取邀请码是一次网络往返,放在消费线程里会把后面所有
     * 群的建档一起堵住。</p>
     *
     * <p>队列满时走 DiscardPolicy 直接丢弃:邀请码是补充事实,丢了下次建档或手动刷新还能补,
     * 不值得为它阻塞调用方(CallerRuns 会退化成同步,正是要避免的)。</p>
     *
     * @return 支持优雅停机的有界后台执行器
     */
    @Bean(name = "groupInviteFetchExecutor")
    public Executor groupInviteFetchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("group-invite-fetch-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
