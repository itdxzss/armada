package com.armada.platform.kafka.consumer.pairing;

/** 协议层 WhatsApp 手机号配对事件的业务投影。 */
public record ProtocolPairingEvent(
        String eventId,
        String eventType,
        String protocolAccountId,
        String clientRefId,
        long occurredAt,
        String workerId,
        String pairingCode,
        Long expiresAt,
        String phone,
        String jid,
        String ownerEndpoint,
        String reason,
        String detectedAccountType) {

    public static final String EVENT_CODE_GENERATED = "pairing.code_generated";
    public static final String EVENT_COMPLETED = "pairing.completed";
    public static final String EVENT_FAILED = "pairing.failed";
}
