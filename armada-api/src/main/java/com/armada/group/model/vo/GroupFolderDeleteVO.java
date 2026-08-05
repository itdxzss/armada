package com.armada.group.model.vo;

/** 群组运营分组批量删除结果。 */
public record GroupFolderDeleteVO(
        int deletedFolderCount,
        int ungroupedGroupCount) {
}
