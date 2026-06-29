package com.armada.platform.kafka.config;

import com.armada.shared.exception.BusinessException;
import java.util.concurrent.Executor;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 协议命令 Kafka 发送链路装配。
 *
 * <p>本配置只注册 Kafka 协议命令发送相关 properties、低频兜底调度能力和后台 dispatch 执行器。
 * 协议层 HTTP 防腐层配置保留在 {@code platform.protocol.config}。</p>
 */
@Configuration
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({
        ProtocolAccountCommandProperties.class,
        ProtocolMasterCommandProperties.class,
        ProtocolCommandPublisherProperties.class,
        ProtocolCommandDispatcherProperties.class,
        ProtocolAccountEventConsumerProperties.class,
        ProtocolGroupEventConsumerProperties.class
})
public class ProtocolKafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProtocolKafkaConfiguration.class);

    private static final int DISPATCH_EXECUTOR_POOL_SIZE = 1;
    private static final int DISPATCH_EXECUTOR_QUEUE_CAPACITY = 1_000;

    /**
     * 注册协议账号事件 consumer 错误处理器。
     *
     * <p>Spring Boot 会把唯一的 {@link CommonErrorHandler} 自动装配到默认
     * {@code kafkaListenerContainerFactory}。listener 抛出的数据库异常等运行时错误会按固定间隔重试,
     * 超过次数后投递到 {@code 原 topic + deadLetterTopicSuffix};消息格式校验失败使用
     * {@link BusinessException} 表达,这类毒消息不重试,直接交给 recoverer,避免反复打日志和占用分区。</p>
     *
     * @param kafkaTemplateProvider KafkaTemplate provider,测试切片未加载 Kafka auto config 时可为空
     * @param properties            协议账号事件 consumer 配置
     * @return Kafka listener 通用错误处理器
     */
    @Bean
    @Profile("kafka")
    public CommonErrorHandler protocolAccountEventConsumerErrorHandler(
            ObjectProvider<KafkaTemplate<Object, Object>> kafkaTemplateProvider,
            ProtocolAccountEventConsumerProperties properties) {
        long retryIntervalMs = nonNegativeOrDefault(properties.getRetryIntervalMs(),
                ProtocolAccountEventConsumerProperties.DEFAULT_RETRY_INTERVAL_MS);
        long maxRetryAttempts = nonNegativeOrDefault(properties.getMaxRetryAttempts(),
                ProtocolAccountEventConsumerProperties.DEFAULT_MAX_RETRY_ATTEMPTS);
        FixedBackOff backOff = new FixedBackOff(retryIntervalMs, maxRetryAttempts);

        KafkaTemplate<Object, Object> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        DefaultErrorHandler errorHandler;
        if (kafkaTemplate == null) {
            errorHandler = new DefaultErrorHandler(backOff);
            log.warn("协议账号事件 consumer 错误处理未找到 KafkaTemplate,失败消息超过重试次数后仅记录日志 "
                            + "retryIntervalMs={} maxRetryAttempts={}",
                    retryIntervalMs, maxRetryAttempts);
        } else {
            String deadLetterTopicSuffix = textOrDefault(properties.getDeadLetterTopicSuffix(),
                    ProtocolAccountEventConsumerProperties.DEFAULT_DEAD_LETTER_TOPIC_SUFFIX);
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                    (record, exception) -> new TopicPartition(record.topic() + deadLetterTopicSuffix,
                            record.partition()));
            errorHandler = new DefaultErrorHandler(recoverer, backOff);
            log.info("协议账号事件 consumer 错误处理已启用 retryIntervalMs={} maxRetryAttempts={} "
                            + "deadLetterTopicSuffix={}",
                    retryIntervalMs, maxRetryAttempts, deadLetterTopicSuffix);
        }
        errorHandler.addNotRetryableExceptions(BusinessException.class);
        return errorHandler;
    }

    /**
     * 注册协议命令 dispatch 后台执行器。
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

    private static long nonNegativeOrDefault(long value, long defaultValue) {
        return value < 0 ? defaultValue : value;
    }

    private static String textOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
