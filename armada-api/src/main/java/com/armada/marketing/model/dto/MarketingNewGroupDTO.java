package com.armada.marketing.model.dto;

/**
 * 账号群快照中首次出现、等待即时营销的群。
 *
 * @param groupLinkId 本地群入口 ID
 * @param groupJid    WhatsApp 群 JID
 * @param groupName   群名快照
 */
public record MarketingNewGroupDTO(
        Long groupLinkId,
        String groupJid,
        String groupName
) {
}
