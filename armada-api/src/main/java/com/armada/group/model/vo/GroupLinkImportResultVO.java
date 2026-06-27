package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接导入结果 VO。
 *
 * @param batchId         生成的批次 ID
 * @param totalRows       解析总行数(空行不计)
 * @param successRows     成功总数(新增 + 收编)
 * @param failedRows      失败总数(重复 + 格式错误)
 * @param duplicateRows   重复失败数量(批内重复 + 已在导入链接中重复导入)
 * @param formatErrorRows 格式错误数量
 * @param errors          格式错误行的描述列表(如"第 3 行:格式错误:...")
 */
public record GroupLinkImportResultVO(
        Long batchId,
        int totalRows,
        int successRows,
        int failedRows,
        int duplicateRows,
        int formatErrorRows,
        List<String> errors) {
}
