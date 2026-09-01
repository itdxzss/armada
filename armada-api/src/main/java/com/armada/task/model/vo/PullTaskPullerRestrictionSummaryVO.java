package com.armada.task.model.vo;

/** 普通拉群任务拉手限制倒计时汇总。 */
public record PullTaskPullerRestrictionSummaryVO(
        long serverNow,
        int restrictedCount,
        Long nextRestrictionUntil) {
}
