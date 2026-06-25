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
         * 导入结果码:1=成功(新增/复活) 2=已存在 3=批内重复 4=格式错误。
         * 配套 {@code resultLabel} 中文标签由后端算好,前端直接展示、无需自行映射。
         */
        int result,

        /** 导入结果中文标签(后端按 result 码算好:成功/已存在/批内重复/格式错误),前端「状态」列直接展示。 */
        String resultLabel,

        /** 失败/已存在原因(成功行为 null;已存在/批内重复/格式错误行有值)。 */
        String failReason,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt) {
}
