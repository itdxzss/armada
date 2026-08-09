package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import org.springframework.stereotype.Component;

/** 群级失败终止执行行、调用、波次和参与者所需依赖。 */
@Component
public record PullTaskGroupExecutionFailureResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullCallMapper callMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskGroupExecutionFailureParticipants participants) {
}
