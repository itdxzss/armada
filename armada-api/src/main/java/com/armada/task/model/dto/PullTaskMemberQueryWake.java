package com.armada.task.model.dto;

/** 成员查询完成后只唤醒身份与阶段仍匹配的单条执行行。 */
public record PullTaskMemberQueryWake(
        long executionId,
        long taskId,
        int expectedExecutionStatus,
        int expectedStage,
        long targetNextRunAt,
        long now
) {
}
