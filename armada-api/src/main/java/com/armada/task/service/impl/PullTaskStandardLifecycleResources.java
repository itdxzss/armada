package com.armada.task.service.impl;

import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import org.springframework.stereotype.Component;

/** LC-01 任务生命周期向执行域传播状态所需的依赖集合。 */
@Component
public record PullTaskStandardLifecycleResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskGroupAccountMapper accountMapper,
        PullTaskAccountActionMapper actionMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskMaterialMemberMapper materialMapper,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchTrigger dispatchTrigger) {
}
