package com.armada.platform.protocol.model.command;

/**
 * 普通拉群提交单方向联系人保存命令时使用的引用模型。
 *
 * <p>账号引用只冻结 Outbox 路由列，payload 仅保存任务和动作行引用；联系人号码在发布时
 * 从动作两端的角色账号快照补全。</p>
 *
 * @param tenantId 所属租户 ID
 * @param pullTaskId 普通拉群任务 ID
 * @param groupExecutionId 群链接执行行 ID
 * @param actionId 联系人动作行 ID，也是 Outbox 聚合 ID
 * @param actor 执行联系人保存的协议账号引用
 */
public record ProtocolPullTaskContactSaveCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef actor
) {
    /** 普通拉群联系人保存命令来源。 */
    public static final String SOURCE = "pull_task_contact_save";

    /** 生成不含账号和联系人号码的持久化引用。 */
    public ProtocolPullTaskContactSaveReference reference() {
        return new ProtocolPullTaskContactSaveReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
