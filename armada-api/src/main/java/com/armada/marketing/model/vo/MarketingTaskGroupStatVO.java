package com.armada.marketing.model.vo;

/**
 * 单账号下一个真实发送群组的聚合统计。
 *
 * @param groupLinkId 实际发送群对应的群链接记录 ID
 * @param groupJid 实际发送群的 WhatsApp JID
 * @param groupLinkUrl 实际发送群的邀请链接
 * @param groupName 实际发送群的最新可用名称
 * @param groupStatus 最新已完成发送记录中的群组检测状态，未确认时为 {@code UNCONFIRMED}
 * @param executionResult 按轮次和尝试次数确定的最新发送结果：{@code SUCCESS} 或 {@code FAILED}；无有效结果时为空
 * @param sentMessageCount 该账号向该群组发送成功的历史累计次数
 * @param failedMessageCount 该账号向该群组发送失败的历史累计次数
 * @param lastAttemptAt 最近一次发送尝试或跳过的毫秒时间戳
 * @param lastSentAt 最近一次发送成功的毫秒时间戳
 * @param lastReason 最近一次发送失败或跳过的原因；最新记录成功时为空
 */
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        String groupStatus,
        String executionResult,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
