package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskCreatorLeaveOperation;
import com.armada.task.model.enums.PullTaskCreatorLeaveProtocolOutcome;

/**
 * 标准拉人任务群主退群协议结果。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 技术动作行 ID
 * @param accountId 执行动作的建群者账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param operation 协议动作
 * @param targetJid 提升管理员时的目标 JID；退群时为空
 * @param outcome 协议结果
 * @param reasonCode 稳定原因码
 * @param reasonMessage 协议原始说明；业务层不会直接展示
 * @param occurredAt 结果发生时间
 */
public record PullTaskCreatorLeaveCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        PullTaskCreatorLeaveOperation operation,
        String targetJid,
        PullTaskCreatorLeaveProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        long occurredAt) {
}
