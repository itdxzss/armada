package com.armada.task.model.vo;

/**
 * 单群料子当前人数及执行历史，人数与次数独立计算。
 *
 * @param retryPendingCount 当前待执行且有已提交历史的不同号码数，是 remainingCount 的子集
 * @param submittedAttemptCount 已提交料子尝试的累计次数，不包含未提交计划和站台
 * @param unconfirmedAttemptCount 已提交尝试中当前结果仍为 UNKNOWN 的次数，是 submittedAttemptCount 的子集
 * @param lastSuccessfulAt 当前成功料子最近入群事实时间；无成功事实时为空
 */
public record PullTaskStandardMaterialSummaryVO(
        int totalCount,
        int successfulCount,
        int failedCount,
        int unknownCount,
        int remainingCount,
        int submittedCount,
        int canceledCount,
        int retryPendingCount,
        long submittedAttemptCount,
        long unconfirmedAttemptCount,
        Long lastSuccessfulAt) {
}
