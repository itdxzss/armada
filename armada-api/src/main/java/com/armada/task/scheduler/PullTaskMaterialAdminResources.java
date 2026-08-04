package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 料子提权事务的执行行、账号和 Outbox 依赖。 */
@Component
public record PullTaskMaterialAdminResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
