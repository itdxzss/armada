package com.armada.platform.protocol.model.command;

/**
 * 普通拉群管理员邀请拉手 Outbox 持久化引用。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 邀请动作 ID
 * @param source 业务来源
 */
public record ProtocolPullTaskPullerInviteReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source
) {
}
