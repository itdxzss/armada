package com.armada.task.service.impl;

import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import org.springframework.stereotype.Component;

/** LC-01 任务生命周期向执行域传播状态所需的依赖集合。 */
@Component
public record PullTaskStandardLifecycleResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskAccountActionMapper actionMapper,
        PullTaskMemberQueryMapper memberQueryMapper,
        PullTaskLifecyclePullResources pull,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchTrigger dispatchTrigger) {
}
