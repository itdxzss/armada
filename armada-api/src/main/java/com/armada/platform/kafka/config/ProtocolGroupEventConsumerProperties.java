package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议群组事件 Kafka consumer 配置。
 *
 * <p>当前用于接收协议层异步回写的 {@code group.health_reported}。真实启用时需激活
 * {@code kafka} profile 并配置 Spring Kafka consumer broker/反序列化等基础参数。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.group-events")
public class ProtocolGroupEventConsumerProperties {

    /** 默认协议群组事件 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.group.events.v1";

    /** 默认 consumer group。 */
    public static final String DEFAULT_GROUP_ID = "armada-api-group-events";

    /** 协议群组事件 topic。 */
    private String topic = DEFAULT_TOPIC;

    /** 群组事件 consumer group。 */
    private String groupId = DEFAULT_GROUP_ID;

    /**
     * 获取协议群组事件 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置协议群组事件 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取群组事件 consumer group。
     *
     * @return Kafka consumer group
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 设置群组事件 consumer group。
     *
     * @param groupId Kafka consumer group
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
