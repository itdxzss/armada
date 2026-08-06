package com.armada.platform.protocol.model.command;

/**
 * 普通拉群成员动作 Outbox 持久化引用。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 账号动作 ID
 * @param source 业务来源
 */
public record ProtocolPullTaskParticipantActionReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source
) {
}
