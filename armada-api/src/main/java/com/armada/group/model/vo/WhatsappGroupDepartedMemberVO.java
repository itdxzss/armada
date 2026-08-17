package com.armada.group.model.vo;

/**
 * 营销导出可读取的 WhatsApp 最近退群事实。
 *
 * @param groupJid 群 JID
 * @param participantJid 成员 JID
 * @param phone 可解析手机号
 * @param exitedAt 退群时间
 * @param exitType LEFT、REMOVED 或 UNKNOWN
 * @param sourceType 事实来源，HISTORY_SYNC、WGP2_NOTIFICATION、WEB_NOTIFICATION 或 BUSINESS_COMMAND
 */
public record WhatsappGroupDepartedMemberVO(
        String groupJid,
        String participantJid,
        String phone,
        Long exitedAt,
        String exitType,
        String sourceType) {
}
