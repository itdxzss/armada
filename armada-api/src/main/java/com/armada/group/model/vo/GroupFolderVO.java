package com.armada.group.model.vo;

/** 群组运营分组列表行。 */
public record GroupFolderVO(
        long id,
        String name,
        long groupCount,
        long createdAt,
        long updatedAt) {
}
