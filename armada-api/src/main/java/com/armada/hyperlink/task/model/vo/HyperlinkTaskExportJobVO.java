package com.armada.hyperlink.task.model.vo;

/** 公共超链导出作业轮询合同。 */
public record HyperlinkTaskExportJobVO(
        long id,
        String exportType,
        String status,
        long snapshotAt,
        String fileName,
        int rowCount,
        String errorMessage,
        long createdAt,
        Long finishedAt,
        boolean downloadReady) { }
