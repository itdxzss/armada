package com.armada.task.model.dto;

/** 冻结波次计划时把参与者聚合绑定到调用和 attempt，不提前写真实拉手。 */
public record PullTaskParticipantPlanBinding(
        long participantId,
        long attemptId,
        long pullCallId,
        long now) {
}
