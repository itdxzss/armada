package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import org.springframework.stereotype.Component;

/** SC-05 资源等待恢复的执行行、账号域与站台候选依赖。 */
@Component
public record PullTaskResourceRecoveryResources(
        PullTaskGroupExecutionMapper executionMapper,
        AccountProtocolLookupService accountLookup,
        PullTaskStationSelectionService stationSelectionService) {
}
