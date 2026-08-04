package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 补充拉手踩链接事务所需的执行行与账号协议身份边界。 */
@Component
public record PullTaskSupplementPullerResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup) {
}
