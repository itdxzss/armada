package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import org.springframework.stereotype.Component;

/** 未知结果收敛所需的任务域 Mapper 集合。 */
@Component
public record PullTaskUnknownResultResources(
        PullTaskAccountActionMapper actionMapper,
        PullTaskPullCallMapper callMapper,
        PullTaskMaterialMemberMapper materialMapper,
        PullTaskGroupAccountMapper accountMapper) {
}
