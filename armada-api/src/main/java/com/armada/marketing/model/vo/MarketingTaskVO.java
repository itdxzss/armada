package com.armada.marketing.model.vo;

/**
 * 营销任务列表行/创建返回视图。
 */
public record MarketingTaskVO(
        Long id,
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        Integer status,
        Integer selectedAccountCount,
        Integer targetGroupCount,
        Integer targetPairCount,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Integer sendPerRound,
        Integer sendIntervalSeconds,
        Boolean onlineCheckEnabled,
        Boolean abnormalGroupSkipped,
        Boolean autoRetryEnabled,
        Integer retryLimit,
        String remark,
        Long startedAt,
        Long lastSentAt,
        Long finishedAt,
        Long createdAt,
        Long updatedAt) {
}
