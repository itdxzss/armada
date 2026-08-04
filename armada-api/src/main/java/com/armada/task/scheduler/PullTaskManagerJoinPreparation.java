package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskManagerJoinWork;

/** 管理员入群事务准备结果：要么可调用协议层，要么已经在事务内收敛。 */
public record PullTaskManagerJoinPreparation(
        PullTaskManagerJoinWork work,
        PullTaskExecutionDispatchResult result) {

    /** @return 创建可执行协议调用的准备结果 */
    public static PullTaskManagerJoinPreparation ready(PullTaskManagerJoinWork work) {
        return new PullTaskManagerJoinPreparation(work, null);
    }

    /** @return 创建已在事务内收敛的结果 */
    public static PullTaskManagerJoinPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskManagerJoinPreparation(null, result);
    }

    /** @return 是否需要继续执行协议调用 */
    public boolean ready() {
        return work != null;
    }
}
