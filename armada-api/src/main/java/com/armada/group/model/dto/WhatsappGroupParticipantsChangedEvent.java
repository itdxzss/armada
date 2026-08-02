package com.armada.group.model.dto;

import java.util.List;

/** 普通 WhatsApp 群成员的精确进退群事件。 */
public record WhatsappGroupParticipantsChangedEvent(
        String eventId,
        Long tenantId,
        Long observerAccountId,
        String protocolAccountId,
        String groupJid,
        String action,
        Long occurredAt,
        String source,
        String sourceEventId,
        List<WhatsappGroupParticipant> participants) {
}
