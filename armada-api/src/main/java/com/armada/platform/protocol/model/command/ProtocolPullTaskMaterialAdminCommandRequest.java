package com.armada.platform.protocol.model.command;

/** 普通拉群把单个 A/a 料子设置为群管理员的 Outbox 命令请求。 */
public record ProtocolPullTaskMaterialAdminCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long materialId,
        Long managerGroupAccountId,
        ProtocolAccountRef actor
) {
    /** 料子提权命令来源。 */
    public static final String SOURCE = "pull_task_material_admin";

    /** 生成不含群、账号和料子号码的持久化引用。 */
    public ProtocolPullTaskMaterialAdminReference reference() {
        return new ProtocolPullTaskMaterialAdminReference(
                tenantId, pullTaskId, groupExecutionId, materialId,
                managerGroupAccountId, SOURCE);
    }
}
