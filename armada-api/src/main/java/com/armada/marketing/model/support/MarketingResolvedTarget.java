package com.armada.marketing.model.support;

import com.armada.marketing.model.entity.MarketingTaskTarget;

/**
 * 一条营销 target 在某次发送中解析出的实际群。
 *
 * @param target      账号或固定群目标
 * @param groupLinkId 本地群入口 ID
 * @param groupJid    WhatsApp 群 JID
 * @param groupName   群名快照
 */
public record MarketingResolvedTarget(
        MarketingTaskTarget target,
        Long groupLinkId,
        String groupJid,
        String groupName
) {
}
