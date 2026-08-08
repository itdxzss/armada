package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskMemberAddPermissionWork;

/** 普通成员添加权限事务准备结果。 */
public record PullTaskMemberAddPermissionPreparation(
        PullTaskMemberAddPermissionWork work,
        PullTaskExecutionDispatchResult result) {

    /** @return 创建可继续调用协议的准备结果 */
    public static PullTaskMemberAddPermissionPreparation ready(
            PullTaskMemberAddPermissionWork work) {
        return new PullTaskMemberAddPermissionPreparation(work, null);
    }

    /** @return 创建已在事务内收敛的准备结果 */
    public static PullTaskMemberAddPermissionPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskMemberAddPermissionPreparation(null, result);
    }

    /** @return 是否需要继续调用协议 */
    public boolean ready() {
        return work != null;
    }
}
