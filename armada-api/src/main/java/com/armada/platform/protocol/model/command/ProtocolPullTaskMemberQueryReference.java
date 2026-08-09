package com.armada.platform.protocol.model.command;

/** 普通拉群成员查询命令在 Outbox 中持久化的轻量业务引用。 */
public record ProtocolPullTaskMemberQueryReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long queryId,
        String source
) {
}
