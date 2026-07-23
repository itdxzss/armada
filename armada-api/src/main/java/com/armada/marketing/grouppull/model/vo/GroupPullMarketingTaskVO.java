package com.armada.marketing.grouppull.model.vo;

/** 拉群营销任务一级列表行。 */
public record GroupPullMarketingTaskVO(
        Long id,
        String taskName,
        Integer status,
        Integer blockReason,
        Integer resourceStatus,
        Integer totalDataCount,
        Integer completedDataCount,
        Integer successGroupCount,
        Integer failedGroupCount,
        Integer marketingAccountTotalCount,
        Integer usedMarketingAccountCount,
        Long createdAt,
        Long taskEndAt) {
}
