package com.armada.platform.protocol.model.result;

/**
 * 协议层群成员列表项。
 *
 * @param jid   WhatsApp 成员 JID
 * @param phone 从 JID 提取的号码,可空
 * @param admin 是否管理员
 * @param owner 是否群主/超级管理员
 * @param role  协议层原始角色,如 admin/superadmin,普通成员为空
 */
public record GroupParticipantResult(
        String jid,
        String phone,
        Boolean admin,
        Boolean owner,
        String role
) {
}
