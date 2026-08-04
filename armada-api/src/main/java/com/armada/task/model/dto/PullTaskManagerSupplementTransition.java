package com.armada.task.model.dto;

import java.util.List;

/** 补充管理员提交后，以执行行版本和资源等待事实 CAS 回到管理员检查点。 */
public record PullTaskManagerSupplementTransition(
        Scope scope,
        Expected expected,
        Target target) {

    /** 乐观锁与时间范围。 */
    public record Scope(long taskId, long executionId, int expectedVersion, long now) {
    }

    /** 允许提交补充指令的当前状态。 */
    public record Expected(
            List<Integer> executionStatuses,
            int waitResourceType,
            List<Integer> stages) {
    }

    /** 提交后的调度检查点。 */
    public record Target(int executionStatus, int stage) {
    }
}
