package com.armada.task.model.dto;

import java.util.List;

/** 任务级人工暂停标记向非终态执行行传播的条件。 */
public record PullTaskExecutionManualChange(
        long taskId,
        List<Integer> eligibleExecutionStatuses,
        Integer manualPaused,
        boolean clearLock,
        long now) {
}
