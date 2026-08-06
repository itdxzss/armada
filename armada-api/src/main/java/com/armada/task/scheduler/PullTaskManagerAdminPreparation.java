package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskManagerAdminWork;

/** 管理员设置事务准备结果：要么可实时核验，要么已在事务内收敛。 */
public record PullTaskManagerAdminPreparation(
        PullTaskManagerAdminWork work,
        PullTaskExecutionDispatchResult result) {

    /** @return 创建可继续实时核验的准备结果 */
    public static PullTaskManagerAdminPreparation ready(PullTaskManagerAdminWork work) {
        return new PullTaskManagerAdminPreparation(work, null);
    }

    /** @return 创建已收敛的准备结果 */
    public static PullTaskManagerAdminPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskManagerAdminPreparation(null, result);
    }

    /** @return 是否需要继续实时核验 */
    public boolean ready() {
        return work != null;
    }
}
