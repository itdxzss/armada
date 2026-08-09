package com.armada.task.service.impl;

import com.armada.task.scheduler.PullTaskPullWaveProgressService;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import org.springframework.stereotype.Component;

/** 聚合逐号码回执需要触发的粘性拉手、群级终止和波次唤醒动作。 */
@Component
public record PullTaskPullCallResultCoordination(
        PullTaskStickyPullerTransactionService stickyPullers,
        PullTaskGroupExecutionFailureService groupFailure,
        PullTaskPullWaveProgressService waveProgress) {
}
