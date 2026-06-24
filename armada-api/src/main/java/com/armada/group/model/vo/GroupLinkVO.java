package com.armada.group.model.vo;

/**
 * 群链接出参(返回前端的视图对象)。
 * 时间字段为 epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。
 */
public record GroupLinkVO(
        /** 群链接 ID。 */
        Long id,

        /** 归一化链接 URL。 */
        String url,

        /** 群名称(可为 null)。 */
        String groupName,

        /** 来源文件名(可为 null)。 */
        String sourceFileName,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt) {
}
