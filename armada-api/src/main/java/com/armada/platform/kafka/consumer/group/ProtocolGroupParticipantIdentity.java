package com.armada.platform.kafka.consumer.group;

/** 协议群角色事件中的 PN/LID 成员身份。 */
public record ProtocolGroupParticipantIdentity(
        String id,
        String lid,
        String phoneNumber) {
}
