package com.armada.task.model.dto;

import java.util.List;

/** 任务结束时把全部非终态执行行推进为放弃终态的条件。 */
public record PullTaskExecutionAbandon(
        long taskId,
        List<Integer> eligibleExecutionStatuses,
        int targetExecutionStatus,
        int manualPaused,
        long finishedAt,
        long now) {
}
