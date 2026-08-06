package com.armada.platform.protocol.model.command;

/**
 * 普通拉群管理员邀请单个拉手的 Outbox 命令请求。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 邀请动作 ID
 * @param actor 执行邀请的管理员协议账号
 */
public record ProtocolPullTaskPullerInviteCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef actor
) {
    /** 管理员邀请拉手命令来源。 */
    public static final String SOURCE = "pull_task_puller_invite";

    /** 生成不含账号、群和目标号码的持久化引用。 */
    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
