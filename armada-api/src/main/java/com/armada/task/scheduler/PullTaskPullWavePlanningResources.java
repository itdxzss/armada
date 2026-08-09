package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import org.springframework.stereotype.Component;

/** 完整波次创建和恢复所需的持久化资源。 */
@Component
public record PullTaskPullWavePlanningResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskPullWavePlanningSelection selection) {
}
