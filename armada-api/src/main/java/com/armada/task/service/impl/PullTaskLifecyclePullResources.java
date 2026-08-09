package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import org.springframework.stereotype.Component;

/** 任务级与单群级生命周期共享的拉人事实资源。 */
@Component
public record PullTaskLifecyclePullResources(
        PullTaskGroupAccountMapper accountMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskMaterialMemberMapper materialMapper,
        PullTaskPullWaveMapper waveMapper) {
}
