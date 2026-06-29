package com.armada.group.model.vo;

/**
 * WS链接分组出参(返回前端的视图对象)。
 * 时间字段为 epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。
 */
public record GroupLinkLabelVO(
        /** 分组 ID。 */
        Long id,

        /** 分组名称。 */
        String name,

        /** 使用国家/区域展示名。 */
        String region,

        /** 备注。 */
        String remark,

        /** 分组下活跃群链接数。 */
        long linkCount,

        /** 分组下未删导入批次数。 */
        long fileCount,

        /** 分组下历史导入总行数。 */
        long totalRows,

        /** 分组下历史导入成功行数(新增+收编)。 */
        long successRows,

        /** 分组下历史导入失败行数。 */
        long failedRows,

        /** 最近一次导入来源文件名。 */
        String latestSourceFile,

        /** 最近一次导入时间,epoch 毫秒(UTC)。 */
        Long latestImportedAt,

        /** 分组维度导入状态:DONE/PARTIAL/FAILED/EMPTY。 */
        String status,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt,

        /** 更新时间,epoch 毫秒(UTC)。 */
        Long updatedAt) {
}
