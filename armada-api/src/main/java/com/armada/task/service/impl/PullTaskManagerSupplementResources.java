package com.armada.task.service.impl;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** 补充管理员服务的数据访问与账号域边界集合。 */
@Component
public record PullTaskManagerSupplementResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskGroupAccountMapper accountMapper,
        PullTaskAccountActionMapper actionMapper,
        AccountProtocolLookupService accountLookup,
        AccountGroupService accountGroupService) {
}
