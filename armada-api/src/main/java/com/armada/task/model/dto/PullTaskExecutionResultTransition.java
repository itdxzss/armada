package com.armada.task.model.dto;

/**
 * 无调度租约时按执行状态、阶段和版本应用协议结果的 CAS 条件。
 *
 * @param executionId 执行行 ID
 * @param taskId 拉群任务 ID
 * @param expectedVersion 期望版本
 * @param expectedExecutionStatus 期望执行状态
 * @param expectedStage 期望阶段
 * @param targetStage 目标阶段
 * @param nextPullerIndex 下一拉手角色序号游标；null 表示保持当前值
 * @param nextRunAt 下次调度时间
 * @param now 回写时间
 */
public record PullTaskExecutionResultTransition(
        long executionId,
        long taskId,
        int expectedVersion,
        int expectedExecutionStatus,
        int expectedStage,
        int targetStage,
        Integer nextPullerIndex,
        long nextRunAt,
        long now
) {
}
