package com.armada.marketing.model.vo;

/**
 * 营销账号树的群节点。
 *
 * @param groupLinkId 群入口 ID
 * @param groupJid    WhatsApp 群 JID
 * @param groupName   群名快照
 * @param linkUrl     群邀请链接
 * @param isAdmin     当前发言号是否群管理员
 * @param membershipStatus 当前账号群关系状态
 * @param membershipStatusText 当前账号群关系状态文案
 * @param statusUpdatedAt 状态事实时间(epoch毫秒)
 */
public record MarketingTreeGroupVO(
        Long groupLinkId,
        String groupJid,
        String groupName,
        String linkUrl,
        Boolean isAdmin,
        String membershipStatus,
        String membershipStatusText,
        Long statusUpdatedAt) {
}
