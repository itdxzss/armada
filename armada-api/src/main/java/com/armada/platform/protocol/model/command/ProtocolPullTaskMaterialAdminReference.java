package com.armada.platform.protocol.model.command;

/** 普通拉群料子提权命令在 Outbox 中持久化的业务引用。 */
public record ProtocolPullTaskMaterialAdminReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long materialId,
        Long managerGroupAccountId,
        String source
) {
}
