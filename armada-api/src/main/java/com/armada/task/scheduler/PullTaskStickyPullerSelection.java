package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.model.entity.PullTaskGroupAccount;

/** 一次派发使用的粘性拉手及其分配代际。 */
public record PullTaskStickyPullerSelection(
        PullTaskGroupAccount role,
        ProtocolAccountRef protocol,
        long assignmentSeq,
        PullTaskExecutionDispatchResult result) {

    /** @return 是否已经把计划调用绑定到可派发拉手 */
    public boolean ready() {
        return role != null && protocol != null && result == null;
    }

    /** 创建已绑定的拉手选择结果。 */
    public static PullTaskStickyPullerSelection ready(
            PullTaskGroupAccount role,
            ProtocolAccountRef protocol,
            long assignmentSeq) {
        return new PullTaskStickyPullerSelection(role, protocol, assignmentSeq, null);
    }

    /** 创建已在事务内收敛的调度结果。 */
    public static PullTaskStickyPullerSelection completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskStickyPullerSelection(null, null, 0L, result);
    }
}
