package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;

/**
 * 任务管理员提权协议结果回写参数。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 管理员设置动作 ID
 * @param accountId 发起提权的既有管理员账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param targetJid 待提权任务管理员 JID
 * @param outcome 协议结果
 * @param reasonCode 协议稳定原因码
 * @param reasonMessage 协议原始说明，业务层不会直接落库
 * @param retryable 协议层重试建议
 * @param occurredAt 结果发生时间
 */
public record PullTaskManagerAdminCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        PullTaskManagerAdminProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
}
