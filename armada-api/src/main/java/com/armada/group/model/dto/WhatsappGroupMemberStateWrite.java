package com.armada.group.model.dto;

/** WhatsApp 群成员最新状态写入参数。 */
public record WhatsappGroupMemberStateWrite(
        Long tenantId,
        String groupJid,
        String participantJid,
        String phone,
        Boolean admin,
        Boolean owner,
        String role,
        boolean inGroup,
        String stateSource,
        long stateUpdatedAt,
        String sourceEventId,
        String snapshotVersion,
        Long observerAccountId) {
}
