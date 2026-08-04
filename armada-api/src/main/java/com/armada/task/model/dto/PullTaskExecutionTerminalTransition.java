package com.armada.task.model.dto;

/** 单群永久结束时推进放弃终态的乐观锁条件。 */
public record PullTaskExecutionTerminalTransition(
        long taskId,
        long executionId,
        int expectedExecutionStatus,
        int expectedVersion,
        int targetExecutionStatus,
        int targetManualPaused,
        long finishedAt,
        long now) {
}
