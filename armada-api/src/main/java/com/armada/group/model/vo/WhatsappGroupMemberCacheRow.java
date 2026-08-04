package com.armada.group.model.vo;

/** 群成员缓存头与成员状态的扁平查询结果。 */
public record WhatsappGroupMemberCacheRow(
        String groupJid,
        String subject,
        Boolean announce,
        Long snapshotAt,
        Long observerAccountId,
        String participantJid,
        String phone,
        Boolean admin,
        Boolean owner,
        String role,
        Boolean inGroup,
        String stateSource,
        Long stateUpdatedAt) {
}
