package com.armada.task.service.impl;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.scheduler.PullTaskStationSelectionService;
import org.springframework.stereotype.Component;

/** 补充站台用例的执行行、账号域和站台选择依赖。 */
@Component
public record PullTaskStationSupplementResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskGroupAccountMapper accountMapper,
        AccountProtocolLookupService accountLookup,
        AccountGroupService accountGroupService,
        PullTaskStationSelectionService stationSelectionService) {
}
