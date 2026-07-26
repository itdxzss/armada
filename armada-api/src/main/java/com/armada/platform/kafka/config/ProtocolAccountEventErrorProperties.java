package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议账号事件 Kafka 消费错误处理配置。
 *
 * <p>状态和群同步 listener 共用重试策略；死信 recoverer 根据原始 topic 分别写入
 * {@code 原 topic + deadLetterTopicSuffix}。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.account-event-errors")
public class ProtocolAccountEventErrorProperties {

    /** 默认消费失败重试间隔，单位毫秒。 */
    public static final long DEFAULT_RETRY_INTERVAL_MS = 1_000L;

    /** 默认消费失败最大重试次数，不包含首次消费。 */
    public static final long DEFAULT_MAX_RETRY_ATTEMPTS = 3L;

    /** 默认死信 topic 后缀。 */
    public static final String DEFAULT_DEAD_LETTER_TOPIC_SUFFIX = ".DLT";

    private long retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS;
    private long maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;
    private String deadLetterTopicSuffix = DEFAULT_DEAD_LETTER_TOPIC_SUFFIX;

    /**
     * 获取消费失败重试间隔。
     *
     * @return 重试间隔，单位毫秒
     */
    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    /**
     * 设置消费失败重试间隔。
     *
     * @param retryIntervalMs 重试间隔，单位毫秒
     */
    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    /**
     * 获取消费失败最大重试次数。
     *
     * @return 最大重试次数
     */
    public long getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /**
     * 设置消费失败最大重试次数。
     *
     * @param maxRetryAttempts 最大重试次数
     */
    public void setMaxRetryAttempts(long maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * 获取死信 topic 后缀。
     *
     * @return topic 后缀
     */
    public String getDeadLetterTopicSuffix() {
        return deadLetterTopicSuffix;
    }

    /**
     * 设置死信 topic 后缀。
     *
     * @param deadLetterTopicSuffix topic 后缀
     */
    public void setDeadLetterTopicSuffix(String deadLetterTopicSuffix) {
        this.deadLetterTopicSuffix = deadLetterTopicSuffix;
    }
}
