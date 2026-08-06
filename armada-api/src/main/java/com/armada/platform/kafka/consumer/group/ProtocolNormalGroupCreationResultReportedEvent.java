package com.armada.platform.kafka.consumer.group;

/** Web/Android 统一回传的新建普群动作最终结果。 */
public record ProtocolNormalGroupCreationResultReportedEvent(
        String eventId,
        Long tenantId,
        Long taskId,
        Long itemId,
        Long memberId,
        String direction,
        String action,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        String commandId,
        int attemptNo,
        String outcome,
        String groupJid,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long timestamp,
        String workerId
) {
}
