package com.armada.platform.protocol.model.command;

/**
 * 标准拉人任务群主退群异步命令请求。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 技术动作行 ID
 * @param action 协议动作
 * @param actor 执行动作的建群者账号
 */
public record ProtocolPullTaskCreatorLeaveCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        Action action,
        ProtocolAccountRef actor) {

    /** 协议回调业务来源。 */
    public static final String SOURCE = "pull_task_creator_leave";

    /** 群主退群链路的两种协议动作。 */
    public enum Action {
        /** 把控端普通成员提升为管理员。 */
        PROMOTE,
        /** 建群者退出群组。 */
        LEAVE
    }

    /** 生成不复制群与账号详情的持久化引用。 */
    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
