package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import java.util.Objects;

/** 普通链接拉群单个 A/a 料子管理员权限变更的协议回调。 */
public record PullTaskMaterialAdminCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long materialId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        PullTaskMaterialAdminProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
    /** 校验协议回调定位字段。 */
    public PullTaskMaterialAdminCallback {
        if (tenantId <= 0 || pullTaskId <= 0 || groupExecutionId <= 0
                || materialId <= 0 || accountId <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("料子提权回调关联 ID 非法");
        }
        if (protocolAccountId == null || protocolAccountId.isBlank()
                || commandId == null || commandId.isBlank()
                || targetJid == null || targetJid.isBlank()) {
            throw new IllegalArgumentException("料子提权回调定位字段不能为空");
        }
        protocolAccountId = protocolAccountId.trim();
        commandId = commandId.trim();
        targetJid = targetJid.trim();
        outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
    }
}
