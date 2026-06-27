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
         * 导入结果码:1=成功 2=失败。
         * 配套 {@code resultLabel} 中文标签由后端算好,前端直接展示、无需自行映射。
         */
        int result,

        /** 导入结果中文标签(后端按 result 码算好:成功/失败),前端「状态」列直接展示。 */
        String resultLabel,

        /** 成功类型:1=新增 2=收编已有群;失败时为空。 */
        Integer successType,

        /** 成功类型中文标签。 */
        String successTypeLabel,

        /** 失败原因(成功行为 null;失败行填重复/格式错误)。 */
        String failReason,

        /** 收编成功时记录已有群入口来源。 */
        Integer existingOrigin,

        /** 收编成功时已有群入口来源中文标签。 */
        String existingOriginLabel,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt) {
}
