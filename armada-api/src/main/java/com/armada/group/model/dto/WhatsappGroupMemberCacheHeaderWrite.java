package com.armada.group.model.dto;

/** WhatsApp 群成员完整快照头写入参数。 */
public record WhatsappGroupMemberCacheHeaderWrite(
        Long tenantId,
        String groupJid,
        String subject,
        Boolean announceOnly,
        long snapshotAt,
        String snapshotVersion,
        Long observerAccountId) {
}
