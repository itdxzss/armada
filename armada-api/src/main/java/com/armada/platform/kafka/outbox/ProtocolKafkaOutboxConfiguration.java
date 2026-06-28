package com.armada.platform.kafka.outbox;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 协议命令 outbox Kafka 装配。
 *
 * <p>本配置只注册 Kafka outbox 发送相关 properties、低频调度能力和后台 dispatch 执行器。
 * 协议层 HTTP 防腐层配置保留在 {@code platform.protocol.config}。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        ProtocolCommandPublisherProperties.class,
        ProtocolCommandDispatcherProperties.class
})
public class ProtocolKafkaOutboxConfiguration {

    private static final int DISPATCH_EXECUTOR_POOL_SIZE = 1;
    private static final int DISPATCH_EXECUTOR_QUEUE_CAPACITY = 1_000;

    /**
     * 注册协议命令 outbox dispatch 后台执行器。
     *
     * <p>请求线程只提交一次 afterCommit 任务;真正 Kafka 发送在该单线程执行器内串行 drain,
     * 避免同 JVM 内大量请求同时创建发送线程。</p>
     *
     * @return dispatch 后台执行器
     */
    @Bean(name = "protocolCommandDispatchExecutor")
    public Executor protocolCommandDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("protocol-command-dispatch-");
        executor.setCorePoolSize(DISPATCH_EXECUTOR_POOL_SIZE);
        executor.setMaxPoolSize(DISPATCH_EXECUTOR_POOL_SIZE);
        executor.setQueueCapacity(DISPATCH_EXECUTOR_QUEUE_CAPACITY);
        executor.initialize();
        return executor;
    }
}
