package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;

/** 管理员设置事务准备结果：提权工作、管理员定点发现或已在事务内收敛。 */
public record PullTaskManagerAdminPreparation(
        PullTaskManagerAdminWork work,
        PullTaskMemberQueryRequest discoveryRequest,
        PullTaskExecutionDispatchResult result) {

    /** @return 创建可继续实时核验的准备结果 */
    public static PullTaskManagerAdminPreparation ready(PullTaskManagerAdminWork work) {
        return new PullTaskManagerAdminPreparation(work, null, null);
    }

    /** @return 创建需要定点发现现有管理员的准备结果 */
    public static PullTaskManagerAdminPreparation discovery(
            PullTaskMemberQueryRequest discoveryRequest) {
        return new PullTaskManagerAdminPreparation(null, discoveryRequest, null);
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

}
