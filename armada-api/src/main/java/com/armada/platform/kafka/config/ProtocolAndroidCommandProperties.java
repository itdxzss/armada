package com.armada.platform.kafka.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Android 协议命令 Kafka topic 配置。
 *
 * <p>生命周期、营销消息和进群命令使用独立 topic，避免不同负载互相阻塞。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.android-commands")
public class ProtocolAndroidCommandProperties implements InitializingBean {

    /** 默认 Android 生命周期命令 topic。 */
    public static final String DEFAULT_LIFECYCLE_TOPIC = "protocol.android.lifecycle.commands.v1";

    /** 默认 Android 营销消息命令 topic。 */
    public static final String DEFAULT_MESSAGE_TOPIC = "protocol.android.message.commands.v1";

    /** 默认 Android 进群命令 topic。 */
    public static final String DEFAULT_GROUP_JOIN_TOPIC = "protocol.android.group-join.commands.v1";

    /** Android 生命周期命令 topic。 */
    private String lifecycleTopic = DEFAULT_LIFECYCLE_TOPIC;

    /** Android 营销消息命令 topic。 */
    private String messageTopic = DEFAULT_MESSAGE_TOPIC;

    /** Android 进群命令 topic。 */
    private String groupJoinTopic = DEFAULT_GROUP_JOIN_TOPIC;

    @Override
    public void afterPropertiesSet() {
        if (isBlank(lifecycleTopic) || isBlank(messageTopic) || isBlank(groupJoinTopic)) {
            throw new IllegalArgumentException("Android 命令 topic 不能为空");
        }
        lifecycleTopic = lifecycleTopic.trim();
        messageTopic = messageTopic.trim();
        groupJoinTopic = groupJoinTopic.trim();
        if (lifecycleTopic.equals(messageTopic)
                || lifecycleTopic.equals(groupJoinTopic)
                || messageTopic.equals(groupJoinTopic)) {
            throw new IllegalArgumentException("Android 命令 topic 必须互不重复");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 获取 Android 生命周期命令 topic。
     *
     * @return Kafka topic
     */
    public String getLifecycleTopic() {
        return lifecycleTopic;
    }

    /**
     * 设置 Android 生命周期命令 topic。
     *
     * @param lifecycleTopic Kafka topic
     */
    public void setLifecycleTopic(String lifecycleTopic) {
        this.lifecycleTopic = lifecycleTopic;
    }

    /**
     * 获取 Android 营销消息命令 topic。
     *
     * @return Kafka topic
     */
    public String getMessageTopic() {
        return messageTopic;
    }

    /**
     * 设置 Android 营销消息命令 topic。
     *
     * @param messageTopic Kafka topic
     */
    public void setMessageTopic(String messageTopic) {
        this.messageTopic = messageTopic;
    }

    /**
     * 获取 Android 进群命令 topic。
     *
     * @return Kafka topic
     */
    public String getGroupJoinTopic() {
        return groupJoinTopic;
    }

    /**
     * 设置 Android 进群命令 topic。
     *
     * @param groupJoinTopic Kafka topic
     */
    public void setGroupJoinTopic(String groupJoinTopic) {
        this.groupJoinTopic = groupJoinTopic;
    }
}
