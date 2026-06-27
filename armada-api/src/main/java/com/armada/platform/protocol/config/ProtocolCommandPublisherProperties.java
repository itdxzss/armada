package com.armada.platform.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议命令 Kafka publisher 配置。
 *
 * <p>当前只控制单条 send 等待 producer ack 的超时时间。Kafka broker 地址和 serializer
 * 仍走 Spring Boot 标准 {@code spring.kafka.*} 配置。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.command-publisher")
public class ProtocolCommandPublisherProperties {

    /** 默认 Kafka send 等待超时时间,单位毫秒。 */
    public static final long DEFAULT_SEND_TIMEOUT_MS = 10_000L;

    /** Kafka send 等待 producer ack 的超时时间,单位毫秒。 */
    private long sendTimeoutMs = DEFAULT_SEND_TIMEOUT_MS;

    /**
     * 获取 Kafka send 等待超时时间。
     *
     * @return Kafka send 等待 producer ack 的超时时间,单位毫秒
     */
    public long getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    /**
     * 设置 Kafka send 等待超时时间。
     *
     * @param sendTimeoutMs Kafka send 等待 producer ack 的超时时间,单位毫秒
     */
    public void setSendTimeoutMs(long sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }
}
