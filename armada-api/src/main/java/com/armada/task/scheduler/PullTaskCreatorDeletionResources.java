package com.armada.task.scheduler;

import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 注销短事务依赖；协议查询始终由事务外处理器调用。 */
@Component
public record PullTaskCreatorDeletionResources(PullTaskMapper tasks,
        PullTaskGroupExecutionMapper executions, PullTaskGroupAccountMapper roles,
        PullTaskAccountActionMapper actions, AccountCreatorDeletionService lifecycle,
        AccountProtocolLookupService accounts, ObjectMapper json) { }
