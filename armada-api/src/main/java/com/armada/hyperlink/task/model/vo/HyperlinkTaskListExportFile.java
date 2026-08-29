package com.armada.hyperlink.task.model.vo;

/** H1 同步 CSV 导出文件。 */
public record HyperlinkTaskListExportFile(
        String filename,
        String contentType,
        byte[] bytes,
        int exportedCount) {
}
