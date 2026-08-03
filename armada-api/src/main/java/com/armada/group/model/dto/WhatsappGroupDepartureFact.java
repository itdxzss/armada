package com.armada.group.model.dto;

/**
 * WhatsApp 协议明确提供的一条退群事实。
 *
 * @param tenantId 租户 ID
 * @param groupJid 群 JID
 * @param participantJid 成员 JID
 * @param phone 可解析手机号
 * @param exitedAt 退群时间
 * @param exitType LEFT 或 REMOVED
 * @param eventAt 协议事实时间
 * @param sourceEventId 源事件 ID
 * @param sourceType HISTORY_SYNC 或 WGP2_NOTIFICATION
 */
public record WhatsappGroupDepartureFact(
        Long tenantId,
        String groupJid,
        String participantJid,
        String phone,
        Long exitedAt,
        String exitType,
        Long eventAt,
        String sourceEventId,
        String sourceType) {
}
