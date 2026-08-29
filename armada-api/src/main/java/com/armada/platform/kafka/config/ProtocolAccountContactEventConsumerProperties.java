package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议账号通讯录快照事件 Kafka consumer 配置。
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.account-contact-events")
public class ProtocolAccountContactEventConsumerProperties {

    /** 默认协议账号通讯录快照事件 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.account.contact-sync.events.v1";

    /** 默认协议账号通讯录快照事件 consumer group。 */
    public static final String DEFAULT_GROUP_ID = "armada-api-account-contact-events";

    /** 默认单实例 listener 并发数。 */
    public static final int DEFAULT_CONCURRENCY = 2;

    /** 默认每次 poll 只取一片重型快照，避免批处理超过 Kafka poll 超时。 */
    public static final int DEFAULT_MAX_POLL_RECORDS = 1;

    private String topic = DEFAULT_TOPIC;
    private String groupId = DEFAULT_GROUP_ID;
    private int concurrency = DEFAULT_CONCURRENCY;
    private int maxPollRecords = DEFAULT_MAX_POLL_RECORDS;

    /**
     * 获取账号通讯录快照事件 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置账号通讯录快照事件 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取账号通讯录快照事件 consumer group。
     *
     * @return consumer group
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 设置账号通讯录快照事件 consumer group。
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

    /**
     * 获取单次 poll 最大快照分片数。
     *
     * @return 单次 poll 最大记录数
     */
    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    /**
     * 设置单次 poll 最大快照分片数。
     *
     * @param maxPollRecords 单次 poll 最大记录数
     */
    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }
}
