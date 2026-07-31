package com.armada.marketing.export.model.vo;

import java.nio.file.Path;

/** 已成功生成且允许当前租户下载的本地导出文件。 */
public record MarketingTaskExportFile(
        Path path,
        String filename,
        String contentType,
        long size) {
}
