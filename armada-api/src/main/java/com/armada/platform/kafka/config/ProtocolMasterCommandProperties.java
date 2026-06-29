package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议 master 命令 Kafka topic 配置。
 *
 * <p>master command topic 只承载协议层 master 已声明支持的命令,例如下线和群链接健康检测;
 * 尚未接入 master 路由的账号上线命令继续使用账号命令 topic。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.kafka.master-commands")
public class ProtocolMasterCommandProperties {

    /** 默认协议 master 命令 topic。 */
    public static final String DEFAULT_TOPIC = "protocol.master.commands.v1";

    /** 协议 master 命令 topic。 */
    private String topic = DEFAULT_TOPIC;

    /**
     * 获取协议 master 命令 topic。
     *
     * @return Kafka topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置协议 master 命令 topic。
     *
     * @param topic Kafka topic
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }
}
