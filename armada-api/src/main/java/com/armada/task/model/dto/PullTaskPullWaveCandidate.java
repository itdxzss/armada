package com.armada.task.model.dto;

/** 初始或重试波次中一个稳定参与者候选。 */
public record PullTaskPullWaveCandidate(
        int participantType,
        long participantRefId,
        String targetPhone,
        String targetJid,
        long failureCount) {
}
