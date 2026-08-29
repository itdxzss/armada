package com.armada.hyperlink.task.model.vo;

/** recipient 状态变化合并后的任务、轮次或账号指标净增量。 */
public record HyperlinkMetricsDelta(
        long tenantId,
        long taskId,
        Long roundId,
        Long accountId,
        int assignedRecipientDelta,
        long sendDelta,
        long successDelta,
        long deliveredDelta,
        long readDelta,
        long failedDelta,
        long fail404Delta,
        Long firstSendAt,
        Long lastSendAt) {
}
