package com.armada.hyperlink.task.model.vo;

/** 到期发送的租户定位；commandCreatedAt 为当前命令固定起点，旧记录回退首次提交时间。 */
public record HyperlinkReconciliationCandidate(
        long tenantId,
        long taskId,
        long recipientId,
        long accountId,
        String commandId,
        int protocolBackend,
        Long commandCreatedAt) {
}
