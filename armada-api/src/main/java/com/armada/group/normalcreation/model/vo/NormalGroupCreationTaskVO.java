package com.armada.group.normalcreation.model.vo;

/** 新建普群任务摘要。 */
public record NormalGroupCreationTaskVO(
        Long id,
        String status,
        Integer totalCount,
        Integer successCount,
        Integer failedCount,
        Long createdAt,
        Long updatedAt) {
}
