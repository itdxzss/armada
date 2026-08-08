package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import org.springframework.stereotype.Component;

/** EX-06 聚合调用、执行行、站台选择和批量人数依赖。 */
@Component
public record PullTaskPullCallPlanningResources(
        PullTaskPullCallMapper pullCallMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskStationSelectionService stationSelectionService,
        PullTaskBatchSizeSelector batchSizeSelector,
        AccountProtocolLookupService accountLookup) {
}
