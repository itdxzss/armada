package com.armada.platform.kafka.consumer.group;

/** 协议层针对一个请求目标返回的当前群成员事实。 */
public record ProtocolGroupMemberFact(
        String targetJid,
        String participantJid,
        String phoneNumber,
        boolean inGroup,
        boolean admin
) {
}
