package com.armada.group.model.vo;

import java.util.List;

/** 可供导出复用的 WhatsApp 群成员完整缓存。 */
public record WhatsappGroupMemberCacheSnapshotVO(
        String groupJid,
        String subject,
        Boolean announce,
        Long snapshotAt,
        Long observerAccountId,
        List<WhatsappGroupMemberStateVO> members) {
}
