package com.armada.task.model.dto;

/** 一次拉人调用提交后，原子推进波次游标与执行行派发时钟。 */
public record PullTaskPullWaveDispatchAdvance(
        Scope scope,
        Target target,
        Execution execution,
        long now) {

    /** 波次更新的乐观锁与游标前置条件。 */
    public record Scope(long waveId, int expectedWaveVersion, int expectedCallSeq) {
    }

    /** 本次提交后的完整波次派发检查点。 */
    public record Target(
            int nextCallSeq,
            int waveStatus,
            long nextDispatchAt,
            Long dispatchCompletedAt) {
    }

    /** 当前执行行的租约与乐观锁身份。 */
    public record Execution(long executionId, int expectedVersion, String lockOwner) {
    }

    /** 校验共享事务输入中的必要身份与单调游标。 */
    public PullTaskPullWaveDispatchAdvance {
        if (scope == null || target == null || execution == null) {
            throw new IllegalArgumentException("波次派发推进参数不能为空");
        }
        if (scope.waveId() <= 0 || execution.executionId() <= 0
                || scope.expectedWaveVersion() <= 0 || execution.expectedVersion() <= 0
                || scope.expectedCallSeq() <= 0
                || target.nextCallSeq() != scope.expectedCallSeq() + 1
                || execution.lockOwner() == null || execution.lockOwner().isBlank()) {
            throw new IllegalArgumentException("波次派发推进身份或游标非法");
        }
    }
}
