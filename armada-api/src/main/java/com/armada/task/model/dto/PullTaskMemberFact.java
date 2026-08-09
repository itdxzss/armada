package com.armada.task.model.dto;

/** 协议层针对单个请求目标返回的在群和管理员事实。 */
public record PullTaskMemberFact(
        String targetJid,
        String participantJid,
        String phoneNumber,
        boolean inGroup,
        boolean admin
) {
}
