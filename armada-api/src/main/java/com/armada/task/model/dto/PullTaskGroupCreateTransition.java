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
        long now,
        String verifiedProfileCommandId) {

    /**
     * 把本次群名、简介回读核验绑定到同一次步骤 CAS；不修改资料动作的协议结果。
     *
     * @param commandId 已核验的资料命令 ID
     * @return 携带核验证据的同一推进参数
     */
    public PullTaskGroupCreateTransition withProfileVerification(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("verified profile command id is required");
        }
        return new PullTaskGroupCreateTransition(
                executionId, expectedVersion, lockOwner, expectedExecutionStatus, expectedStage,
                expectedStep, targetExecutionStatus, targetStage, targetStep, createOperationId,
                createAttemptCount, groupSubject, groupJid, normalizedLink, inviteCode, groupLinkId,
                manualPaused, reasonCode, reasonMessage, nextRunAt, now, commandId);
    }
}
