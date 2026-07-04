package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销任务详情视图,包含任务主信息和账号×群目标明细。
 */
public record MarketingTaskDetailVO(
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
        Long updatedAt,
        List<MarketingTaskTargetVO> targets) {
}
