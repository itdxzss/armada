package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import org.springframework.stereotype.Component;

/** 聚合拉人波次统一结算所需的数据访问依赖。 */
@Component
public record PullTaskPullWaveSettlementResources(
        PullTaskMapper taskMapper,
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskMaterialMemberMapper materialMapper) {
}
