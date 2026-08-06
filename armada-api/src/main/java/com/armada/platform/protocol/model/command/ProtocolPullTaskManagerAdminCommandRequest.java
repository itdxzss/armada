package com.armada.platform.protocol.model.command;

/**
 * 普通拉群既有管理员把任务管理员设为管理员的 Outbox 命令请求。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 管理员设置动作 ID
 * @param actor 执行提权的既有管理员协议账号
 */
public record ProtocolPullTaskManagerAdminCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef actor
) {
    /** 管理员设置命令来源。 */
    public static final String SOURCE = "pull_task_manager_admin";

    /** 生成不含账号、群和目标号码的持久化引用。 */
    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
