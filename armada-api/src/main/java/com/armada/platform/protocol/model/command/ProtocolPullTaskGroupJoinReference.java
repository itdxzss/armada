package com.armada.platform.protocol.model.command;

/**
 * 普通群链接管理员踩链接命令在 Outbox 中持久化的业务引用。
 *
 * @param tenantId 所属租户 ID
 * @param pullTaskId 普通拉群任务 ID
 * @param groupExecutionId 群链接执行行 ID
 * @param actionId 踩链接动作行 ID
 * @param source 命令来源，固定为 pull_task_manager_join
 */
public record ProtocolPullTaskGroupJoinReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source
) {
}
