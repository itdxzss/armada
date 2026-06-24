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

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt,

        /** 更新时间,epoch 毫秒(UTC)。 */
        Long updatedAt) {
}
