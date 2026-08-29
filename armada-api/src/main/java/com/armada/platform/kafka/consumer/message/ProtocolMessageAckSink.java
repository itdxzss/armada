package com.armada.platform.kafka.consumer.message;

/** 统一 ACK 的唯一业务路由。 */
public interface ProtocolMessageAckSink {
    boolean supports(ProtocolMessageAckEvent event);
    void handleAck(ProtocolMessageAckEvent event);
}
