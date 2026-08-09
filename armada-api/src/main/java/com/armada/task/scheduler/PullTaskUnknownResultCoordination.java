package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 聚合未知结果收敛后的执行行与波次推进动作。 */
@Component
public record PullTaskUnknownResultCoordination(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullCallReconciliationService pullCalls,
        PullTaskPullWaveProgressService waveProgress) {
}
