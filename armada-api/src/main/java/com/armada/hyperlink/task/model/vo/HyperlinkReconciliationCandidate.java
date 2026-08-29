package com.armada.hyperlink.task.model.vo;

/** 到期 UNKNOWN/SENDING 使用原命令恢复的租户定位。 */
public record HyperlinkReconciliationCandidate(
        long tenantId,
        long taskId,
        long recipientId,
        long accountId,
        String commandId,
        int protocolBackend,
        Long submittedAt) {
}
