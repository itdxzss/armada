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
        Long stateUpdatedAt,
        String sourceEventId) {

    public WhatsappGroupMemberStateVO(
            String participantJid,
            String phone,
            Boolean admin,
            Boolean owner,
            String role,
            boolean inGroup,
            String stateSource,
            Long stateUpdatedAt) {
        this(participantJid, phone, admin, owner, role, inGroup,
                stateSource, stateUpdatedAt, null);
    }
}
