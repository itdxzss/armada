package com.armada.group.model.vo;

/**
 * 群链接导入明细出参(返回前端的视图对象)。
 *
 * <p>时间字段为 epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。</p>
 */
public record GroupLinkImportDetailVO(

        /** 文件内行号(从 1 起)。 */
        int lineNo,

        /** 群名称(导入时填,可为 null)。 */
        String groupName,

        /** 原文链接(含格式错误行)。 */
        String rawUrl,

        /** 来源文件名(text 手填导入为 null)。 */
        String sourceFileName,

        /**
         * 导入结果码:1=成功新增 2=收编 3=批内重复 4=格式错误。
         * 前端负责将数值映射为可读标签。
         */
        int result,

        /** 失败原因(result≥3 时有值,其余为 null)。 */
        String failReason,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt) {
}
