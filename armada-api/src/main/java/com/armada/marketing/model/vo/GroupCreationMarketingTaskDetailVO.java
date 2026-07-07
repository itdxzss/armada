package com.armada.marketing.model.vo;

import java.util.List;

public record GroupCreationMarketingTaskDetailVO(
        Long id,
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        Long marketingTaskId,
        Integer status,
        Integer matchedItemCount,
        Integer unmatchedFileCount,
        Integer successCount,
        Integer failedCount,
        Integer abandonedCount,
        Integer sendIntervalSeconds,
        String groupNamePrefix,
        String remark,
        Long finishedAt,
        Long createdAt,
        Long updatedAt,
        List<GroupCreationMarketingItemVO> items) {
}
