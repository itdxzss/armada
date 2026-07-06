package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销任务明细页账号维度统计。
 */
public record MarketingTaskAccountTargetVO(
        Long accountId,
        String accountPhone,
        Integer status,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason,
        List<MarketingTaskGroupStatVO> groups) {
}
