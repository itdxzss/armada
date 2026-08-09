package com.armada.platform.kafka.consumer.group;

import java.util.List;

/** Web/Android 统一群成员查询结果事件。 */
public record ProtocolGroupMembersResultReportedEvent(
        String eventId,
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long queryId,
        String purpose,
        long accountId,
        String protocolAccountId,
        String protocolBackend,
        String commandId,
        int attemptNo,
        String outcome,
        String groupJid,
        List<ProtocolGroupMemberFact> members,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long timestamp,
        String workerId
) {
}
