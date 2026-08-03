package com.armada.platform.kafka.consumer.account;

import java.util.List;

/**
 * Android 协议上报的一批 WhatsApp 退群事实。
 *
 * @param eventId 内部事件 ID
 * @param tenantId 租户 ID
 * @param accountId 观察账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param groupJid 群 JID
 * @param sourceType HISTORY_SYNC 或 WGP2_NOTIFICATION
 * @param occurredAt 上报时间
 * @param participants 退群成员事实
 */
public record ProtocolGroupDepartureEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String sourceType,
        Long occurredAt,
        List<Participant> participants) {

    /** 单个 WhatsApp 退群成员事实。 */
    public record Participant(
            String participantJid,
            String phone,
            String exitType,
            Long exitedAt,
            String sourceEventId) {
    }
}
