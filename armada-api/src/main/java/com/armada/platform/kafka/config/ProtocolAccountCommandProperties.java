package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议账号命令 Kafka topic 配置。
 *
 * <p>上线、下线等账号命令先写入 outbox,再由 dispatcher 发送到该 topic。
 * broker、serializer 等仍走 Spring Boot 标准 {@code spring.kafka.*} 配置。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.account-commands")
public class ProtocolAccountCommandProperties {

    /** 默认协议账号命令 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.account.commands.v1";

    /** 协议账号命令 topic。 */
    private String topic = DEFAULT_TOPIC;

    /**
     * 获取协议账号命令 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置协议账号命令 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }
}
