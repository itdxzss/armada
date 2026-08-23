package com.armada.group.model.vo;

/**
 * 群组运营分组列表行。
 *
 * @param id 分组 ID
 * @param name 分组名称
 * @param systemBuiltin 是否系统内置；系统分组不可改名、删除或作为任务资源池
 * @param groupCount 当前可用于普通拉群的群链接数量
 * @param createdAt 创建时间，epoch 毫秒
 * @param updatedAt 更新时间，epoch 毫秒
 */
public record GroupFolderVO(
        long id,
        String name,
        boolean systemBuiltin,
        long groupCount,
        long createdAt,
        long updatedAt) {
}
