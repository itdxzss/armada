package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Android 协议账号上线命令 Kafka topic 配置。
 *
 * <p>Android 上线命令先进入独立 topic,便于和现有 Baileys Web 协议层隔离部署、灰度和回滚。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.android-commands")
public class ProtocolAndroidCommandProperties {

    /** 默认 Android 协议命令 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.android.commands.v1";

    /** Android 协议命令 topic。 */
    private String topic = DEFAULT_TOPIC;

    /**
     * 获取 Android 协议命令 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置 Android 协议命令 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }
}
