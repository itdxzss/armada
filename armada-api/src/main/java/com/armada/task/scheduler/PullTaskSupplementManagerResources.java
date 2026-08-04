package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 补充管理员事务的执行行与账号域依赖。 */
@Component
public record PullTaskSupplementManagerResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup) {
}
