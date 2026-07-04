package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "armada.protocol.kafka.message-events")
public class ProtocolMessageEventConsumerProperties {
    public static final String DEFAULT_TOPIC = "protocol.message.events.v1";
    public static final String DEFAULT_GROUP_ID = "armada-api-message-events";
    public static final long DEFAULT_RETRY_INTERVAL_MS = 1_000L;
    public static final long DEFAULT_MAX_RETRY_ATTEMPTS = 3L;
    public static final String DEFAULT_DEAD_LETTER_TOPIC_SUFFIX = ".DLT";

    private String topic = DEFAULT_TOPIC;
    private String groupId = DEFAULT_GROUP_ID;
    private long retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS;
    private long maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;
    private String deadLetterTopicSuffix = DEFAULT_DEAD_LETTER_TOPIC_SUFFIX;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public long getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(long maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public String getDeadLetterTopicSuffix() {
        return deadLetterTopicSuffix;
    }

    public void setDeadLetterTopicSuffix(String deadLetterTopicSuffix) {
        this.deadLetterTopicSuffix = deadLetterTopicSuffix;
    }
}
