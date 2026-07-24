package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议账号状态事件 Kafka consumer 配置。
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.account-state-events")
public class ProtocolAccountStateEventConsumerProperties {

    /** 默认协议账号状态事件 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.account.state.events.v1";

    /** 默认协议账号状态事件 consumer group。 */
    public static final String DEFAULT_GROUP_ID = "armada-api-account-state-events";

    /** 默认单实例 listener 并发数。 */
    public static final int DEFAULT_CONCURRENCY = 4;

    private String topic = DEFAULT_TOPIC;
    private String groupId = DEFAULT_GROUP_ID;
    private int concurrency = DEFAULT_CONCURRENCY;

    /**
     * 获取账号状态事件 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置账号状态事件 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取账号状态事件 consumer group。
     *
     * @return consumer group
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 设置账号状态事件 consumer group。
     *
     * @param groupId consumer group
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * 获取单实例 listener 并发数。
     *
     * @return 并发数
     */
    public int getConcurrency() {
        return concurrency;
    }

    /**
     * 设置单实例 listener 并发数。
     *
     * @param concurrency 并发数
     */
    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
