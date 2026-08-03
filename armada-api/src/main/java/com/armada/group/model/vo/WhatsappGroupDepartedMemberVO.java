package com.armada.group.model.vo;

/**
 * 营销导出可读取的 WhatsApp 最近退群事实。
 *
 * @param groupJid 群 JID
 * @param participantJid 成员 JID
 * @param phone 可解析手机号
 * @param exitedAt 退群时间
 * @param exitType LEFT 或 REMOVED
 */
public record WhatsappGroupDepartedMemberVO(
        String groupJid,
        String participantJid,
        String phone,
        Long exitedAt,
        String exitType) {
}
