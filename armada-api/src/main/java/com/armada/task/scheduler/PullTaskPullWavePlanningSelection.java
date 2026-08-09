package com.armada.task.scheduler;

import org.springframework.stereotype.Component;

/** 完整波次规划所需的批量人数与站台选择策略。 */
@Component
public record PullTaskPullWavePlanningSelection(
        PullTaskStationSelectionService stationSelectionService,
        PullTaskBatchSizeSelector batchSizeSelector) {
}
