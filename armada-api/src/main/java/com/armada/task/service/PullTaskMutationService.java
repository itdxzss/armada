package com.armada.task.service;

import java.util.List;

/** 拉群任务公共变更服务。 */
public interface PullTaskMutationService {

    /**
     * 按任务类型和状态策略批量软删当前租户任务。
     *
     * @param ids 待删除任务 ID
     * @return 实际删除数量
     */
    int batchDelete(List<Long> ids);
}
