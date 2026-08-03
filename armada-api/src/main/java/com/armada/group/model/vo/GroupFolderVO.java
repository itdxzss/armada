package com.armada.group.model.vo;

/**
 * 群组列表运营分组管理出参。
 *
 * @param id 分组 ID
 * @param name 分组名称
 * @param groupCount 当前关联的活跃群组数
 * @param createdAt 创建时间(epoch 毫秒)
 * @param updatedAt 更新时间(epoch 毫秒)
 */
public record GroupFolderVO(
        Long id,
        String name,
        long groupCount,
        Long createdAt,
        Long updatedAt
) {
}
