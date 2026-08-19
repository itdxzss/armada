package com.armada.marketing.model.vo;

/**
 * 单账号下一个真实发送群组的聚合统计。
 *
 * @param groupLinkId 实际发送群对应的群链接记录 ID
 * @param groupJid 实际发送群的 WhatsApp JID
 * @param groupLinkUrl 实际发送群的邀请链接
 * @param groupName 实际发送群的最新可用名称
 * @param membershipStatus 当前账号群关系状态
 * @param groupStatus 最后有效尝试归一出的群组状态，值为 NORMAL、ACCOUNT_BANNED、GROUP_BANNED、
 *                    NO_PERMISSION、KICKED_OUT 或 UNCONFIRMED
 * @param executionResult 按轮次、尝试次数和记录 ID 确定的最后可展示结果：{@code SUCCESS}、
 *                        {@code FAILED}、{@code SKIPPED} 或 {@code WAITING}；无记录时为空
 * @param executionReason 发送失败或业务跳过时的原因，成功、等待或无记录时为空
 * @param sentMessageCount 该账号向该群组发送成功的历史累计次数
 * @param failedMessageCount 该账号向该群组发送失败的历史累计次数
 * @param skippedMessageCount 该账号向该群组业务跳过的历史累计次数
 * @param lastAttemptAt 最近一次已提交、完成或跳过的毫秒时间戳；等待阶段为空
 * @param lastSentAt 最近一次发送成功的毫秒时间戳
 * @param lastReason 最近一次发送失败或跳过的原因；最新记录成功时为空
 */
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        String membershipStatus,
        String groupStatus,
        String executionResult,
        String executionReason,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Integer skippedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
