package com.armada.task.model.dto;

/** 建群阶段内部步骤的租约、版本与目标事实原子推进参数。 */
public record PullTaskGroupCreateTransition(
        long executionId,
        int expectedVersion,
        String lockOwner,
        int expectedExecutionStatus,
        int expectedStage,
        int expectedStep,
        int targetExecutionStatus,
        int targetStage,
        int targetStep,
        String createOperationId,
        Integer createAttemptCount,
        String groupSubject,
        String groupJid,
        String normalizedLink,
        String inviteCode,
        Long groupLinkId,
        Integer manualPaused,
        String reasonCode,
        String reasonMessage,
        long nextRunAt,
        long now) {
}
