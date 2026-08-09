package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;

/** 聚合批量拉人提交所需的执行行、波次、调用和逐号码 Mapper。 */
public record PullTaskBatchAddPersistence(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper) {
}
