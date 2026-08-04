package com.armada.task.model.dto;

/** 单个料子的入群结果；Mapper 兼容入口会补充允许的前置状态。 */
public record PullTaskMaterialPullResult(
        long id,
        int pullStatus,
        PullTaskFactResult fact,
        long now) {

    /** 不允许用空事实覆盖已有结果字段。 */
    public PullTaskMaterialPullResult {
        if (fact == null) {
            fact = PullTaskFactResult.empty();
        }
    }
}
