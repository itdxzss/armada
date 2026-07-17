package com.armada.platform.protocol.model.result;

/**
 * 固定操作账号读取的 WhatsApp 群邀请信息。
 *
 * @param groupJid   WhatsApp 群 JID
 * @param inviteCode 群邀请码
 * @param inviteUrl  完整群邀请链接，协议成功时必须非空
 */
public record GroupInviteResult(
        String groupJid,
        String inviteCode,
        String inviteUrl
) {
}
