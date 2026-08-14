package com.armada.platform.protocol.model.command;

/**
 * 普通群链接任务提交账号踩链接命令时使用的引用模型。
 *
 * <p>账号引用只用于冻结 Outbox 路由列，不会写入 {@code payload_json}。Outbox payload 仅保存
 * 任务、执行行和动作行引用，发布时再从冻结业务事实补全邀请码与账号快照。</p>
 *
 * @param tenantId 所属租户 ID
 * @param pullTaskId 普通拉群任务 ID
 * @param groupExecutionId 群链接执行行 ID
 * @param actionId 踩链接动作行 ID，也是 Outbox 聚合 ID
 * @param account 冻结的协议账号路由引用
 */
public record ProtocolPullTaskGroupJoinCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef account
) {
    /** 普通群链接踩链接命令的兼容来源值。 */
    public static final String SOURCE = "pull_task_manager_join";

    /**
     * 生成允许持久化到 Outbox 的轻量引用。
     *
     * @return 不含账号、邀请码和凭据的业务引用
     */
    public ProtocolPullTaskGroupJoinReference reference() {
        return new ProtocolPullTaskGroupJoinReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
