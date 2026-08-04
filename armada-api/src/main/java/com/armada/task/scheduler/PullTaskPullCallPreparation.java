package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskPullCall;

/** 单次拉人调用计划的事务准备结果。 */
public record PullTaskPullCallPreparation(
        PullTaskPullCall call,
        PullTaskExecutionDispatchResult result) {

    /** @return 已持久化完整调用计划 */
    public static PullTaskPullCallPreparation ready(PullTaskPullCall call) {
        return new PullTaskPullCallPreparation(call, null);
    }

    /** @return 已在事务内收敛为调度结果 */
    public static PullTaskPullCallPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskPullCallPreparation(null, result);
    }

    /** @return 是否存在可继续联系人/批量拉人的完整调用 */
    public boolean ready() {
        return call != null;
    }
}
