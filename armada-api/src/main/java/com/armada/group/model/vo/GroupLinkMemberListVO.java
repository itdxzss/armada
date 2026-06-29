package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接明细页的实时成员列表。
 *
 * @param groupLinkId 群链接 ID
 * @param groupJid    WhatsApp 群 JID
 * @param total       本次协议层返回的成员数
 * @param members     成员列表;不落库,只返回当前实时快照
 */
public record GroupLinkMemberListVO(
        Long groupLinkId,
        String groupJid,
        int total,
        List<GroupLinkMemberVO> members
) {
}
