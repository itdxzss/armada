package com.armada.platform.protocol.model.command;

/** 普通拉群群成员事实查询的 Outbox 命令请求。 */
public record ProtocolPullTaskMemberQueryCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long queryId,
        ProtocolAccountRef actor
) {
    /** 成员查询命令来源。 */
    public static final String SOURCE = "pull_task_member_query";

    /** 生成不含目标名单和群信息的持久化引用。 */
    public ProtocolPullTaskMemberQueryReference reference() {
        return new ProtocolPullTaskMemberQueryReference(
                tenantId, pullTaskId, groupExecutionId, queryId, SOURCE);
    }
}
