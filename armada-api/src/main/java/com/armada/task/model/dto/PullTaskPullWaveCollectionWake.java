package com.armada.task.model.dto;

/** 回执关闭调用后，仅唤醒匹配收集态波次的执行行。 */
public record PullTaskPullWaveCollectionWake(
        long executionId,
        long pullWaveId,
        int expectedExecutionStatus,
        int expectedStage,
        long targetNextRunAt,
        long now) {
}
