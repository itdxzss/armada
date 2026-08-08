package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import java.util.List;

/** 参与者当前聚合状态基于活动 attempt 的条件转换。 */
public record PullTaskParticipantAggregateTransition(
        Scope scope,
        Expected expected,
        Target target,
        PullTaskFactResult result) {

    /** 聚合行、产生事实的 attempt 与更新时间。 */
    public record Scope(long participantId, long attemptId, long now) {
    }

    /** 允许的聚合状态及精确失败计数。 */
    public record Expected(List<Integer> statuses, long failureCount) {
        public Expected {
            statuses = List.copyOf(statuses);
            if (statuses.isEmpty()) {
                throw new IllegalArgumentException("允许的参与者原状态不能为空");
            }
        }
    }

    /** 目标聚合状态、失败计数和当前调用/attempt 指针。 */
    public record Target(
            int status,
            long failureCount,
            Long pullCallId,
            Long activeAttemptId) {

        /** @return 目标是否为料子成功或站台在群 */
        public boolean isSuccess() {
            return status == PullTaskMaterialPullStatus.SUCCESS.code()
                    || status == PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
        }
    }

    /** 固化非空值。 */
    public PullTaskParticipantAggregateTransition {
        if (scope == null || expected == null || target == null) {
            throw new IllegalArgumentException("参与者聚合转换参数不能为空");
        }
        if (result == null) {
            result = PullTaskFactResult.empty();
        }
    }
}
