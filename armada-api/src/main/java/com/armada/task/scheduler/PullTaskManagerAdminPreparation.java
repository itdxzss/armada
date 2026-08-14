package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskManagerAdminWork;

/** 管理员设置事务准备结果：提权工作、管理员定点发现或已在事务内收敛。 */
public record PullTaskManagerAdminPreparation(
        PullTaskManagerAdminWork work,
        PullTaskManagerAdminDiscoveryWork discovery,
        PullTaskExecutionDispatchResult result) {

    /** @return 创建可继续实时核验的准备结果 */
    public static PullTaskManagerAdminPreparation ready(PullTaskManagerAdminWork work) {
        return new PullTaskManagerAdminPreparation(work, null, null);
    }

    /** @return 创建需要定点发现现有管理员的准备结果 */
    public static PullTaskManagerAdminPreparation discovery(
            PullTaskManagerAdminDiscoveryWork discovery) {
        return new PullTaskManagerAdminPreparation(null, discovery, null);
    }

    /** @return 创建已收敛的准备结果 */
    public static PullTaskManagerAdminPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskManagerAdminPreparation(null, null, result);
    }

    /** @return 是否需要继续实时核验 */
    public boolean ready() {
        return work != null;
    }

    /** @return 是否需要执行管理员定点发现 */
    public boolean discoveryReady() {
        return discovery != null;
    }
}
