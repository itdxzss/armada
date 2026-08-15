package com.armada.platform.protocol.model.command;

/**
 * 普通拉群任务管理员应用单项群设置的 Outbox 命令请求。
 *
 * <p>一条命令只承载一个设置项。具体设置哪一项由动作行的 {@code action_type} 决定，
 * 发布时由 hydrator 补全，因此本请求和 Outbox 引用都不携带设置项本身。</p>
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 群设置动作 ID
 * @param manager 执行群设置的任务管理员协议账号
 */
public record ProtocolPullTaskGroupSettingsCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef manager
) {
    /** 群设置命令来源。 */
    public static final String SOURCE = "pull_task_group_settings";

    /** 生成不含账号、群和设置项的持久化引用。 */
    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
