package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** EX-04 聚合执行行和账号域依赖，保持事务服务构造器参数有界。 */
@Component
public record PullTaskPullerInviteResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
