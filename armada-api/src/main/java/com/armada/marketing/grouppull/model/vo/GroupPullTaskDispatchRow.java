package com.armada.marketing.grouppull.model.vo;

/** 跨租户调度一条拉群任务所需的最小投影。 */
public record GroupPullTaskDispatchRow(Long tenantId, Long ownerUserId, Long taskId) {
}
