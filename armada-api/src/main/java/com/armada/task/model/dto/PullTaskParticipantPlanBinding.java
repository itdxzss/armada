package com.armada.task.model.dto;

/** 冻结波次计划时绑定调用和 attempt，不提前写已提交状态或真实拉手。 */
public record PullTaskParticipantPlanBinding(
        long participantId,
        long attemptId,
        long pullCallId,
        long now) {
}
