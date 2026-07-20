package com.armada.platform.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议命令 Kafka publisher 配置。
 *
 * <p>控制单条 send 等待 producer ack 的超时时间和批量发送的最大在途数。Kafka broker 地址和 serializer
 * 仍走 Spring Boot 标准 {@code spring.kafka.*} 配置。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.command-publisher")
public class ProtocolCommandPublisherProperties {

    /** 默认 Kafka send 等待超时时间,单位毫秒。 */
    public static final long DEFAULT_SEND_TIMEOUT_MS = 10_000L;

    /** 默认 Kafka 同时在途发送数。 */
    public static final int DEFAULT_MAX_IN_FLIGHT = 100;

    /** Kafka send 等待 producer ack 的超时时间,单位毫秒。 */
    private long sendTimeoutMs = DEFAULT_SEND_TIMEOUT_MS;

    /** Kafka 同时在途发送数。 */
    private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;

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

    /**
     * 获取 Kafka 同时在途发送数。
     *
     * @return Kafka 同时在途发送数
     */
    public int getMaxInFlight() {
        return maxInFlight;
    }

    /**
     * 设置 Kafka 同时在途发送数。
     *
     * @param maxInFlight Kafka 同时在途发送数
     * @throws IllegalArgumentException 最大在途数不大于 0 时抛出
     */
    public void setMaxInFlight(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("协议命令 Kafka 最大在途数必须大于 0");
        }
        this.maxInFlight = maxInFlight;
    }
}
