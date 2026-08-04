package com.armada.task.model.vo;

/** 单群逐号码结果聚合；所有数值均来自料子事实行。 */
public record PullTaskStandardMaterialSummaryVO(
        int totalCount,
        int successfulCount,
        int failedCount,
        int unknownCount,
        int remainingCount,
        int submittedCount,
        int canceledCount) {
}
