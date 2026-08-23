package com.armada.group.model.vo;

/**
 * 拉人任务从自定义分组动态领取的群组资源。
 *
 * @param groupLinkId 群组列表句柄 ID
 * @param groupJid WhatsApp 群 JID
 * @param normalizedLink 当前有效邀请链接
 * @param inviteCode 当前有效邀请码
 */
public record GroupPoolResourceVO(
        long groupLinkId,
        String groupJid,
        String normalizedLink,
        String inviteCode) {
}
