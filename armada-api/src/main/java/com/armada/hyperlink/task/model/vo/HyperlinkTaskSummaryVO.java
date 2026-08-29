package com.armada.hyperlink.task.model.vo;

/** 详情抽屉顶部公共任务统计摘要。 */
public record HyperlinkTaskSummaryVO(
        long id,
        String taskName,
        int recipientTotal,
        long sendTotal,
        long successNum,
        long deliveredNum,
        long readNum,
        long failedNum,
        long unregisteredNum,
        int usedAccountCount,
        int invalidAccountCount,
        long clickUvNum,
        long clickTotal,
        int actualConcurrency,
        long executionDurationSec,
        Long metricsUpdatedAt,
        Long firstVisitAt,
        Long lastVisitAt) { }
