package com.armada.task.scheduler;

import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 管理员设置短事务使用的执行行、候选和 Outbox 依赖。 */
@Component
public record PullTaskManagerAdminResources(
        PullTaskGroupExecutionMapper executionMapper,
        GroupExecutionAccountSelector promoterSelector,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
