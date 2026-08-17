package com.armada.group.model.dto;

/** 当前群模型本地展示字段的写入参数。 */
public record GroupCurrentLocalProfileWrite(
        Long groupLinkId,
        String displayName,
        boolean displayNameObserved,
        String remark,
        boolean remarkObserved,
        String avatarUrl,
        boolean avatarObserved,
        long updatedAt) {
}
