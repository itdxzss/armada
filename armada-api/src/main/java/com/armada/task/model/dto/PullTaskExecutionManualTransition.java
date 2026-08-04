package com.armada.task.model.dto;

/** 单群人工暂停标记的乐观锁条件更新。 */
public record PullTaskExecutionManualTransition(
        long taskId,
        long executionId,
        int expectedExecutionStatus,
        int expectedVersion,
        int expectedManualPaused,
        int targetManualPaused,
        boolean clearLock,
        long now) {
}
