package com.armada.platform.protocol.model.command;

/** 普通拉群站台和料子批量入群命令在 Outbox 中持久化的业务引用。 */
public record ProtocolPullTaskBatchAddReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long pullCallId,
        String source
) {
}
