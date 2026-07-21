package com.armada.marketing.model.support;

/**
 * 营销发送 attempt 的一次结果回写参数。
 *
 * <p>成功和失败共用群状态快照；SQL 根据调用的方法分别消费成功字段或失败字段。
 * {@code attemptId + commandId} 共同标识当前可落地的协议命令，防止旧命令迟到覆盖重试结果。</p>
 *
 * @param attemptId           发送尝试 ID
 * @param commandId           当前协议命令 ID
 * @param messageId           WhatsApp 消息 ID，失败时为空
 * @param reasonCode          失败原因码，成功时为空
 * @param reasonMessage       失败原因描述，成功时为空
 * @param groupJid            协议返回的群 JID
 * @param groupStatus         群状态快照
 * @param groupStatusReason   群状态判定原因
 * @param groupStatusCheckedAt 群状态判定时间(epoch 毫秒)
 * @param resultAt            结果时间(epoch 毫秒)
 */
public record MarketingSendAttemptResult(
        Long attemptId,
        String commandId,
        String messageId,
        String reasonCode,
        String reasonMessage,
        String groupJid,
        String groupStatus,
        String groupStatusReason,
        Long groupStatusCheckedAt,
        long resultAt
) {
}
