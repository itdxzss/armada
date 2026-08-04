package com.armada.task.model.vo;

/** 普通群链接任务详情顶部的真实执行与料子汇总。 */
public record PullTaskStandardTaskSummaryVO(
        int totalGroupCount,
        int executingGroupCount,
        int waitingResourceGroupCount,
        int completedGroupCount,
        int failedGroupCount,
        int abandonedGroupCount,
        int totalMemberCount,
        int successfulMemberCount,
        int failedMemberCount,
        int unknownMemberCount,
        int remainingMemberCount) {
}
