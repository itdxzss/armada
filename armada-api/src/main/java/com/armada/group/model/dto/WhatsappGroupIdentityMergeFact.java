package com.armada.group.model.dto;

/**
 * WhatsApp 协议观察到的一次成员身份合并事实。
 *
 * <p>协议 modify 事件表示同一个人的身份形态发生变化，同时给出 PN 与 LID 两种身份。控端据此把
 * 两个身份补进同一行成员记录，不推断在群与否，也不推断角色——这次观察里两者都没出现。</p>
 *
 * @param tenantId 租户 ID
 * @param groupJid 群 JID
 * @param pnJid 号码形态 JID，必须以 @s.whatsapp.net 结尾
 * @param lidJid LID 形态 JID，必须以 @lid 结尾
 * @param phone 可解析手机号，缺失时不覆盖库中已有号码
 * @param eventAt 协议事实时间（epoch 毫秒）
 * @param sourceEventId 源事件 ID
 */
public record WhatsappGroupIdentityMergeFact(
        Long tenantId,
        String groupJid,
        String pnJid,
        String lidJid,
        String phone,
        Long eventAt,
        String sourceEventId) {
}
