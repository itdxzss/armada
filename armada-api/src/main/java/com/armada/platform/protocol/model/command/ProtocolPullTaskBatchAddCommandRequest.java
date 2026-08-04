package com.armada.platform.protocol.model.command;

/** 普通拉群一次站台和料子批量入群的 Outbox 命令请求。 */
public record ProtocolPullTaskBatchAddCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long pullCallId,
        ProtocolAccountRef actor
) {
    /** 批量拉人命令来源。 */
    public static final String SOURCE = "pull_task_batch_add";

    /** 生成不含群、账号和参与者号码的持久化引用。 */
    public ProtocolPullTaskBatchAddReference reference() {
        return new ProtocolPullTaskBatchAddReference(
                tenantId, pullTaskId, groupExecutionId, pullCallId, SOURCE);
    }
}
