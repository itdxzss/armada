package com.armada.marketing.grouppull.model.vo;

/** 跨租户调度一条拉群执行所需的最小投影。 */
public record GroupPullExecutionDispatchRow(Long tenantId, Long ownerUserId, Long executionId) {
}
