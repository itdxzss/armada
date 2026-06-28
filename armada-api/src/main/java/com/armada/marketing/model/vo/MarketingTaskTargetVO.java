package com.armada.marketing.model.vo;

/**
 * 营销任务目标明细视图,一行表示一个账号对一个群的发送目标。
 */
public record MarketingTaskTargetVO(
        Long id,
        Long accountId,
        String accountPhone,
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        Integer status,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Integer retryCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
