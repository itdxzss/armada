package com.armada.task.service.impl;

import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import org.springframework.stereotype.Component;

/** LC-02 单群生命周期向执行事实传播状态所需的 Mapper 集合。 */
@Component
public record PullTaskStandardExecutionLifecycleResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskGroupAccountMapper accountMapper,
        PullTaskAccountActionMapper actionMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskMaterialMemberMapper materialMapper,
        ProtocolCommandOutboxService outboxService) {
}
