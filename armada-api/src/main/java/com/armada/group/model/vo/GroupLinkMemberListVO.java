package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接明细页最后一次完整成员快照。
 *
 * @param groupLinkId 群链接 ID
 * @param groupJid    WhatsApp 群 JID
 * @param total       已持久化成员快照数量
 * @param members     最后一次完整成员快照
 */
public record GroupLinkMemberListVO(
        Long groupLinkId,
        String groupJid,
        int total,
        List<GroupLinkMemberVO> members
) {
}
