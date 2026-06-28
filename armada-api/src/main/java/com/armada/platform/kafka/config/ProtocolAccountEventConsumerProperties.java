package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议账号事件 Kafka consumer 配置。
 *
 * <p>默认只订阅账号事件 topic。真实启用时需激活 {@code kafka} profile 并配置
 * Spring Kafka consumer broker/反序列化等基础参数。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.account-events")
public class ProtocolAccountEventConsumerProperties {

    /** 默认协议账号事件 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.account.events.v1";

    /** 默认 consumer group。 */
    public static final String DEFAULT_GROUP_ID = "armada-api-account-events";

    /** 默认消费失败重试间隔,单位毫秒。 */
    public static final long DEFAULT_RETRY_INTERVAL_MS = 1_000L;

    /** 默认消费失败最大重试次数;不包含首次消费。 */
    public static final long DEFAULT_MAX_RETRY_ATTEMPTS = 3L;

    /** 默认死信 topic 后缀。 */
    public static final String DEFAULT_DEAD_LETTER_TOPIC_SUFFIX = ".DLT";

    /** 协议账号事件 topic。 */
    private String topic = DEFAULT_TOPIC;

    /** 账号事件 consumer group。 */
    private String groupId = DEFAULT_GROUP_ID;

    /** 消费失败重试间隔,单位毫秒。 */
    private long retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS;

    /** 消费失败最大重试次数;不包含首次消费。 */
    private long maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;

    /** 死信 topic 后缀;最终 topic 为原 topic + 该后缀。 */
    private String deadLetterTopicSuffix = DEFAULT_DEAD_LETTER_TOPIC_SUFFIX;

    /**
     * 获取协议账号事件 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置协议账号事件 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取账号事件 consumer group。
     *
     * @return Kafka consumer group
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 设置账号事件 consumer group。
     *
     * @param groupId Kafka consumer group
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * 获取消费失败重试间隔。
     *
     * @return 消费失败重试间隔,单位毫秒
     */
    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    /**
     * 设置消费失败重试间隔。
     *
     * @param retryIntervalMs 消费失败重试间隔,单位毫秒
     */
    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    /**
     * 获取消费失败最大重试次数。
     *
     * @return 消费失败最大重试次数;不包含首次消费
     */
    public long getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /**
     * 设置消费失败最大重试次数。
     *
     * @param maxRetryAttempts 消费失败最大重试次数;不包含首次消费
     */
    public void setMaxRetryAttempts(long maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * 获取死信 topic 后缀。
     *
     * @return 死信 topic 后缀
     */
    public String getDeadLetterTopicSuffix() {
        return deadLetterTopicSuffix;
    }

    /**
     * 设置死信 topic 后缀。
     *
     * @param deadLetterTopicSuffix 死信 topic 后缀
     */
    public void setDeadLetterTopicSuffix(String deadLetterTopicSuffix) {
        this.deadLetterTopicSuffix = deadLetterTopicSuffix;
    }
}
