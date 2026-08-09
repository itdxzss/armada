package com.armada.task.scheduler;

import org.springframework.stereotype.Component;

/** 聚合拉人执行阶段的波次路由依赖。 */
@Component
public record PullTaskPullExecutionDispatchResources(
        PullTaskPullWavePlanningTransactionService waves,
        PullTaskStickyPullerTransactionService pullers,
        PullTaskPullWaveSettlementTransactionService settlement,
        PullTaskPullerStationContactProcessor contacts,
        PullTaskBatchAddProcessor batch) {
}
