package com.armada.group.model.vo;

/**
 * 本地群表刷新后的账号可见群关系快照。
 *
 * @param groupLinkId 营销目标选择使用的本地 group_link ID
 * @param groupJid    WhatsApp 群 JID
 * @param groupName   本地群名和协议群名兜底后的展示名
 * @param linkUrl     本地 group_link URL,通常为导入链接或 wa://group/{jid}
 * @param admin       账号是否为该群管理员
 */
public record AccountGroupMembershipSnapshot(
        Long groupLinkId,
        String groupJid,
        String groupName,
        String linkUrl,
        Boolean admin
) {
}
