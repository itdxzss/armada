package com.armada.account.model.vo;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
import java.time.ZoneOffset;

public record AccountOnlineAttemptLogVO(
        Long id,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        String commandId,
        String batchId,
        Long proxyId,
        String source,
        String fromState,
        String toState,
        String diagnosisCode,
        String diagnosisClass,
        Integer rawCode,
        String rawReason,
        String recoverability,
        String actionTaken,
        String workerId,
        String evidenceJson,
        Long occurredAt,
        Long createdAt) {

    public static AccountOnlineAttemptLogVO from(AccountOnlineAttemptLog row) {
        return new AccountOnlineAttemptLogVO(
                row.getId(),
                row.getAccountId(),
                row.getProtocolAccountId(),
                row.getOnlineAttemptId(),
                row.getPreviousOnlineAttemptId(),
                row.getCommandId(),
                row.getBatchId(),
                row.getProxyId(),
                row.getSource(),
                row.getFromState(),
                row.getToState(),
                row.getDiagnosisCode(),
                row.getDiagnosisClass(),
                row.getRawCode(),
                row.getRawReason(),
                row.getRecoverability(),
                row.getActionTaken(),
                row.getWorkerId(),
                row.getEvidenceJson(),
                row.getOccurredAt() == null ? null : row.getOccurredAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
