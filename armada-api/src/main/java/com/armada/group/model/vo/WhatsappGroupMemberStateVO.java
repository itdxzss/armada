package com.armada.group.model.vo;

/** WhatsApp 群成员缓存中的最新状态。 */
public record WhatsappGroupMemberStateVO(
        String participantJid,
        String phone,
        Boolean admin,
        Boolean owner,
        String role,
        boolean inGroup,
        String stateSource,
        Long stateUpdatedAt) {
}
