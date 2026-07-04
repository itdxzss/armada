package com.armada.platform.kafka.consumer.message;

public record ProtocolMessageSendResultReportedEvent(
        String eventId,
        Long tenantId,
        Long marketingTaskId,
        Long targetId,
        Long attemptId,
        Long roundNo,
        String protocolAccountId,
        String groupJid,
        String commandId,
        boolean success,
        String messageId,
        String reasonCode,
        String reasonMessage,
        Long timestamp,
        String workerId
) {
}
