package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import org.springframework.stereotype.Component;

/** 聚合批量拉人命令、拉手状态、持久化检查点和静默策略。 */
@Component
public record PullTaskBatchAddResources(
        PullTaskBatchAddPersistence persistence,
        AccountProtocolLookupService accountLookup,
        ProtocolCommandOutboxService outboxService,
        PullTaskOperationDelayPolicy delayPolicy) {
}
