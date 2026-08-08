package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import java.util.List;

/** 逐号码执行记录的租户隔离生命周期 CAS 参数。 */
public record PullTaskParticipantAttemptTransition(
        Scope scope,
        Expected expected,
        Target target,
        PullTaskFactResult result) {

    /** 执行记录定位与更新时间。 */
    public record Scope(long attemptId, long now) {
    }

    /** 允许的前置生命周期。 */
    public record Expected(List<Integer> lifecycleStatuses) {
        public Expected {
            lifecycleStatuses = List.copyOf(lifecycleStatuses);
            if (lifecycleStatuses.isEmpty()) {
                throw new IllegalArgumentException("允许的执行记录原状态不能为空");
            }
        }
    }

    /** 目标生命周期与本次协议事实。 */
    public record Target(
            int lifecycleStatus,
            String protocolOutcome,
            PullTaskParticipantExecutionState executionState,
            Long releasedAt) {

        /** @return 活动态写 1，关闭、释放或取消时写 NULL */
        public Integer activeSlot() {
            return PullTaskParticipantAttemptStatus.active(lifecycleStatus) ? 1 : null;
        }
    }

    /** 固化非空值，避免 XML 执行时出现不完整转换。 */
    public PullTaskParticipantAttemptTransition {
        if (scope == null || expected == null || target == null) {
            throw new IllegalArgumentException("执行记录转换参数不能为空");
        }
        if (result == null) {
            result = PullTaskFactResult.empty();
        }
    }
}
