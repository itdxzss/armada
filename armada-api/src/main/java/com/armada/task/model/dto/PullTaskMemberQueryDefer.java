package com.armada.task.model.dto;

/** 创建或等待成员查询时，以当前租约 CAS 释放执行行直到查询截止时间。 */
public record PullTaskMemberQueryDefer(
        long executionId,
        long taskId,
        int expectedVersion,
        int expectedExecutionStatus,
        int expectedStage,
        String lockOwner,
        long nextRunAt,
        long now
) {
}
