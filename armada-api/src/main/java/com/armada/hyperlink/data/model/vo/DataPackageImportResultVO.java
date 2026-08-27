package com.armada.hyperlink.data.model.vo;

import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次 TXT 导入的稳定计数结果。
 *
 * @param importId 导入审计 ID
 * @param mode 导入模式
 * @param generation 本次写入代次
 * @param totalRows 非空行总数
 * @param acceptedRows 实际写入号码数
 * @param invalidRows 格式非法行数
 * @param duplicatedRows 文件内及包内重复行数
 * @param phoneCountAfterImport 导入后当前代号码总数
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataPackageImportResultVO(
        Long importId,
        DataPackageImportMode mode,
        int generation,
        int totalRows,
        int acceptedRows,
        int invalidRows,
        int duplicatedRows,
        int phoneCountAfterImport) {
}
