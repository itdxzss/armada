package com.armada.task.model.dto;

/** 数据包当前逻辑执行的真实物料事实；旧群记录只用于保留外部动作不确定性。 */
public record GroupDataPackageTaskFact(long phoneId, long allocationVersion,
        long taskId, int executionSeq, int pullStatus, String pullReasonCode,
        Long activeAttemptId, Long pullCallId, long pullFailureCount,
        String taskStatus, Long taskDeletedAt, int executionStatus,
        boolean uncertainHistory, boolean priorConsumed) { }
