package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import org.springframework.stereotype.Component;

/** EX-05 聚合执行行、账号域与调用查询依赖。 */
@Component
public record PullTaskPullerStationContactResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        PullTaskPullCallMapper pullCallMapper,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
