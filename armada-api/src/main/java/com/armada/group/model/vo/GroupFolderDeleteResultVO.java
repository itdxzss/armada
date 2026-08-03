package com.armada.group.model.vo;

/**
 * 删除群组列表运营分组结果。
 *
 * @param deletedFolderCount 删除的分组数
 * @param ungroupedGroupCount 被移入未分组的群组数
 */
public record GroupFolderDeleteResultVO(
        int deletedFolderCount,
        int ungroupedGroupCount
) {
}
