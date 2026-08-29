package com.armada.platform.kafka.consumer.message;

/** 协议层统一消息 ACK；hyperlink 分支不依赖 marketing attempt。 */
public record ProtocolMessageAckEvent(
        String eventId,
        Long tenantId,
        String source,
        Long hyperlinkTaskId,
        Long hyperlinkRecipientId,
        String commandId,
        Long accountId,
        String protocolId,
        String protocolAccountId,
        String jid,
        String targetKind,
        String messageId,
        String ackStatus,
        Boolean success,
        String reasonCode,
        String reasonMessage,
        Long timestamp,
        String workerId) {
}
