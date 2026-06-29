package com.armada.group.model.dto;

import java.util.List;

/**
 * 群链接导入请求 DTO。
 *
 * <p>{@code lines} 由 Controller 用 {@code FileLinesExtractor.extract(file, text)} 填充后传入 Service。</p>
 *
 * @param labelId   目标WS链接分组 ID
 * @param batchName 批次名称(来源文件/批次名称,非必填;留空存 NULL)
 * @param text      手填多行文本(原样保留,供 batch.sourceFileName 为 null 时追溯)
 * @param lines     FileLinesExtractor 解析后的行列表(text+file 合并)
 * @param sourceFileName 上传文件原始名称;纯文本导入为 null
 */
public record GroupLinkImportDTO(
        Long labelId,
        String batchName,
        String text,
        List<String> lines,
        String sourceFileName) {}
