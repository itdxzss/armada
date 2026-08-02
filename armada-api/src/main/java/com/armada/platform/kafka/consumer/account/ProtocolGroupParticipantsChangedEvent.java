package com.armada.platform.kafka.consumer.account;

import java.util.List;

/** 协议层普通 WhatsApp 群成员 add/remove/leave 事件。 */
public record ProtocolGroupParticipantsChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String action,
        Long occurredAt,
        String source,
        String sourceEventId,
        String workerId,
        List<Participant> participants) {

    /** 单个变更成员，memberJid 是跨快照/事件的稳定身份键。 */
    public record Participant(String memberJid, String jid, String phone) {
    }
}
