package com.armada.platform.kafka.consumer.group;

import java.util.List;

/** Web/Android 统一群成员角色变化事件。 */
public record ProtocolGroupParticipantChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        String groupJid,
        String action,
        List<ProtocolGroupParticipantIdentity> participants,
        String operator,
        String source,
        long occurredAt,
        String workerId) {
}
