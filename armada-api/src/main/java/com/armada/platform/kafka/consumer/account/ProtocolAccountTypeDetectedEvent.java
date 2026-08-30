package com.armada.platform.kafka.consumer.account;

/** 协议层账号类型检测事件。 */
public record ProtocolAccountTypeDetectedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String commandId,
        String protocolBackend,
        Long credentialVersion,
        Integer declaredAccountType,
        String detectedAccountType,
        String verificationLevel,
        String source,
        Long detectedAt,
        String workerId
) {
}
