package com.armada.platform.kafka.consumer.account;

import java.util.List;

/** Android 实时群成员进群事件。 */
public record ProtocolGroupJoinEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String sourceType,
        Long occurredAt,
        List<Participant> participants) {

    /** 单个进群成员事实。 */
    public record Participant(
            String participantJid,
            String phone,
            Long joinedAt,
            String sourceEventId) {
    }
}
