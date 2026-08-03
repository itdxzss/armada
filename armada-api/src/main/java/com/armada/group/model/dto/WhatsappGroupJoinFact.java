package com.armada.group.model.dto;

/**
 * WhatsApp 协议明确提供的一条进群事实。
 *
 * @param tenantId 租户 ID
 * @param groupJid 群 JID
 * @param participantJid 成员 JID
 * @param phone 可解析手机号
 * @param joinedAt 进群时间
 * @param eventAt 协议事实时间
 * @param sourceEventId 源事件 ID
 * @param observerAccountId 观察到事件的 Armada 账号 ID
 */
public record WhatsappGroupJoinFact(
        Long tenantId,
        String groupJid,
        String participantJid,
        String phone,
        Long joinedAt,
        Long eventAt,
        String sourceEventId,
        Long observerAccountId) {
}
