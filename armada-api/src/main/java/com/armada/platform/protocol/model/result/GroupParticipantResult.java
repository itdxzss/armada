package com.armada.platform.protocol.model.result;

/**
 * 协议层群成员列表项。
 *
 * @param jid   WhatsApp 成员 JID,协议返回原值;可能是 PN 形式(@s.whatsapp.net)或 LID 形式(@lid)
 * @param pnJid 成员的 PN 形式 JID,可空。WhatsApp 群成员列表已逐步只返回 LID,但两侧协议仍分别通过
 *              Web 的 {@code phoneNumber} 与 Android 的 {@code phone} 给出号码,据此还原 PN 身份,
 *              使同一个人的两种身份能落在同一行成员记录上;无法确认时为空,禁止由 LID 数字反推号码
 * @param phone 从 JID 提取的号码,可空
 * @param admin 是否管理员
 * @param owner 是否群主/超级管理员
 * @param role  协议层原始角色,如 admin/superadmin,普通成员为空
 */
public record GroupParticipantResult(
        String jid,
        String pnJid,
        String phone,
        Boolean admin,
        Boolean owner,
        String role
) {
}
