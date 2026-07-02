package com.armada.account.service;

public record AccountOfflineDiagnosedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        String commandId,
        String batchId,
        Long proxyId,
        String source,
        String from,
        String to,
        String diagnosisCode,
        String diagnosisClass,
        Integer rawCode,
        String rawReason,
        String recoverability,
        String actionTaken,
        Long occurredAt,
        String workerId,
        String evidenceJson) {
}
