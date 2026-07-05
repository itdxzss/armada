package com.armada.marketing.model.vo;

/**
 * 营销任务目标明细视图。
 *
 * <p>GROUP_FIXED 行表示一个账号对一个固定群的发送目标;ACCOUNT_DYNAMIC 行只表示账号目标,
 * 实际群会在每轮发送前解析并写入发送 attempt。</p>
 */
public record MarketingTaskTargetVO(
        Long id,
        Long accountId,
        String accountPhone,
        String targetScope,
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
