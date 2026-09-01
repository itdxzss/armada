package com.armada.platform.kafka.consumer.account;

/** 协议平台上报的账号外联限制事件；active=false 同样作为解除事实保存。 */
public record ProtocolAccountRestrictedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        boolean active,
        Long restrictedUntil,
        String enforcementType,
        String reasonCode,
        String rawCode,
        String reasonMessage,
        Long occurredAt,
        String workerId) {
}
