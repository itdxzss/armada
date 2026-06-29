package com.armada.group.model.vo;

/**
 * 群成员实时查询目标。
 *
 * @param groupLinkId 群链接 ID
 * @param groupJid    WhatsApp 群 JID,来自 group_link_preview
 */
public record GroupMemberLookupTarget(
        Long groupLinkId,
        String groupJid
) {
}
