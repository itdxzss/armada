package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;

/**
 * 拉群单项群设置协议结果回写参数。
 *
 * <p>一条命令一个设置项，因此命令级 {@code outcome} 就是该设置项的结果，
 * 不需要逐项结果数组。设置项本身由动作行的 {@code action_type} 推出。</p>
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 群设置动作 ID
 * @param accountId 执行设置的任务管理员账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param outcome 协议结果
 * @param reasonCode 协议稳定原因码
 * @param reasonMessage 协议原始说明，业务层不会直接落库
 * @param occurredAt 结果发生时间
 */
public record PullTaskGroupSettingsCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        PullTaskGroupSettingsProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        long occurredAt
) {
}
