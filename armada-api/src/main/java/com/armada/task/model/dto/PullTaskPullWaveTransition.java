package com.armada.task.model.dto;

/** 拉人波次状态、派发游标和时间字段的一次乐观锁转换。 */
public record PullTaskPullWaveTransition(
        Scope scope,
        Target target,
        long now) {

    /** 波次定位与前置状态。 */
    public record Scope(
            long id,
            long groupExecutionId,
            int expectedStatus,
            int expectedVersion) {
    }

    /** 波次转换后的完整进度。 */
    public record Target(
            int status,
            int nextCallSeq,
            long nextDispatchAt,
            Long dispatchCompletedAt,
            Long settledAt) {
    }

    /** 校验转换的必要组成，避免 Mapper 收到不完整参数。 */
    public PullTaskPullWaveTransition {
        if (scope == null || target == null) {
            throw new IllegalArgumentException("波次转换参数不能为空");
        }
    }
}
