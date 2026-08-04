package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 管理员入群事务服务的执行行与账号域依赖。 */
@Component
public record PullTaskManagerJoinResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        PullTaskParentCompletionService parentCompletionService,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
