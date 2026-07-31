package com.armada.marketing.export.model.vo;

/** 前端轮询营销任务导出状态所需的最小信息。 */
public record MarketingTaskExportJobVO(
        Long id,
        String exportMode,
        String status,
        Long snapshotAt,
        String fileName,
        Integer summaryRowCount,
        Integer detailRowCount,
        String errorMessage,
        Long createdAt,
        Long finishedAt,
        boolean downloadReady) {
}
