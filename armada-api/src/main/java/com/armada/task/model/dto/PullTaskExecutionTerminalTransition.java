package com.armada.task.model.dto;

/** 单群进入不可恢复终态时使用的乐观锁条件与业务原因。 */
public record PullTaskExecutionTerminalTransition(
        long taskId,
        long executionId,
        int expectedExecutionStatus,
        int expectedVersion,
        int targetExecutionStatus,
        int targetManualPaused,
        String reasonCode,
        String reasonMessage,
        long finishedAt,
        long now) {
}
