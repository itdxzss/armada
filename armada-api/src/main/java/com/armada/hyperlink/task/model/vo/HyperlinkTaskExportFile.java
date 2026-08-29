package com.armada.hyperlink.task.model.vo;

import java.nio.file.Path;

/** 服务端校验后的公共超链导出文件。 */
public record HyperlinkTaskExportFile(
        Path path,
        String filename,
        String contentType,
        long size) { }
