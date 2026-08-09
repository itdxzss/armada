package com.armada.task.model.dto;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskRosterObservation;

/** 一个未知逐号码 attempt 的名单核实收口参数。 */
public record PullTaskUncertainParticipantSettlement(
        Context context,
        PullTaskPullCallMemberAttempt attempt,
        PullTaskRosterObservation observation,
        long now) {

    /** 租户、调用及执行行上下文。 */
    public record Context(
            long tenantId,
            PullTaskPullCall call,
            PullTaskGroupExecution execution) {
    }

    /** 拒绝不完整的名单收口参数。 */
    public PullTaskUncertainParticipantSettlement {
        if (context == null || context.call() == null || context.execution() == null
                || attempt == null || observation == null) {
            throw new IllegalArgumentException("名单核实收口参数不能为空");
        }
    }
}
