package com.armada.group.model.vo;

import java.util.List;

/**
 * 群组列表筛选分组及全量记录统计，不受页面其他筛选条件影响。
 *
 * @param totalGroupCount 本租户全部未删除群入口数量，包含系统分组
 * @param unassignedGroupCount 未分组的群入口数量
 * @param folders 按现有顺序返回的运营分组选项，包含零群分组
 */
public record GroupFolderFilterOptionsVO(
        long totalGroupCount,
        long unassignedGroupCount,
        List<Option> folders) {

    /**
     * 带群组列表记录数的分组选项。
     *
     * @param id 分组 ID
     * @param name 分组名称
     * @param groupCount 本组未删除群入口数量，包含所有健康状态
     */
    public record Option(long id, String name, long groupCount) {
    }
}
