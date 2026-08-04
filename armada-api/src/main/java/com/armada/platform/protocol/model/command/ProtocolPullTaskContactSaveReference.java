package com.armada.platform.protocol.model.command;

/** 普通拉群联系人保存命令在 Outbox 中持久化的业务引用。 */
public record ProtocolPullTaskContactSaveReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source
) {
}
