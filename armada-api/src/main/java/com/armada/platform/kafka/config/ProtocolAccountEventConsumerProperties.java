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

    /** 协议账号事件 topic。 */
    private String topic = DEFAULT_TOPIC;

    /** 账号事件 consumer group。 */
    private String groupId = DEFAULT_GROUP_ID;

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
}
