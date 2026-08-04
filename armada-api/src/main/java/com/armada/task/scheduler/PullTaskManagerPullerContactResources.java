package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** EX-03 为控制事务服务构造器参数数量而聚合的执行行与账号域依赖。 */
@Component
public record PullTaskManagerPullerContactResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        ProtocolCommandOutboxService outboxService,
        PullTaskExecutionDispatchProperties properties) {
}
