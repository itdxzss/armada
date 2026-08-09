package com.armada.task.model.dto;

/** 波次结算后替换活动波次指针或推进后续阶段的执行行 CAS 参数。 */
public record PullTaskPullWaveSettlementAdvance(
        Scope scope,
        Target target,
        long now) {

    /** 当前执行行、租约和被结算波次身份。 */
    public record Scope(
            long executionId,
            int expectedVersion,
            String lockOwner,
            long expectedPullWaveId) {
    }

    /** 结算后的活动波次、阶段和调度时间。 */
    public record Target(Long activePullWaveId, int stage, long nextRunAt) {
    }

    /** 校验执行行结算推进的必要身份。 */
    public PullTaskPullWaveSettlementAdvance {
        if (scope == null || target == null
                || scope.executionId() <= 0 || scope.expectedVersion() <= 0
                || scope.expectedPullWaveId() <= 0
                || scope.lockOwner() == null || scope.lockOwner().isBlank()) {
            throw new IllegalArgumentException("波次结算推进参数非法");
        }
    }
}
