package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;

/**
 * 管理员邀请拉手协议结果回写参数。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 邀请动作 ID
 * @param accountId 管理员账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param targetJid 被邀请拉手 JID
 * @param outcome 邀请结果
 * @param reasonCode 原因码
 * @param reasonMessage 脱敏原因说明
 * @param retryable 协议层重试建议；业务不自动重试邀请
 * @param occurredAt 结果发生时间
 */
public record PullTaskPullerInviteCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        PullTaskPullerInviteProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
}
