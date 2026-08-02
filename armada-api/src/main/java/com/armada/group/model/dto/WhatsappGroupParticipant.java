package com.armada.group.model.dto;

/** 协议层观察到的一个 WhatsApp 群成员身份与角色。 */
public record WhatsappGroupParticipant(
        String memberJid,
        String participantJid,
        String phone,
        String role,
        Boolean admin,
        Boolean owner) {
}
