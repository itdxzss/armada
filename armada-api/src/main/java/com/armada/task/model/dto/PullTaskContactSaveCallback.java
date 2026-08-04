package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskContactSaveOutcome;

/**
 * 联系人保存协议结果回写参数。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 账号动作 ID
 * @param accountId 动作发起账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号；联系人动作按需求只提交一次
 * @param outcome 成功、明确失败或结果未知
 * @param reasonCode 原因码
 * @param reasonMessage 脱敏原因说明
 * @param retryable 协议层重试建议；联系人业务不据此自动重试
 * @param occurredAt 结果发生时间，epoch 毫秒
 */
public record PullTaskContactSaveCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        PullTaskContactSaveOutcome outcome,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
}
