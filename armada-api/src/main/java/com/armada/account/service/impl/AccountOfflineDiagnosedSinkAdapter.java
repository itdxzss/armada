package com.armada.account.service.impl;

import com.armada.account.service.AccountOfflineDiagnosedEvent;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountOfflineDiagnosedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountOfflineDiagnosedSink;
import org.springframework.stereotype.Service;

@Service
public class AccountOfflineDiagnosedSinkAdapter implements ProtocolAccountOfflineDiagnosedSink {

    private final AccountOnlineAttemptLogService service;

    public AccountOfflineDiagnosedSinkAdapter(AccountOnlineAttemptLogService service) {
        this.service = service;
    }

    @Override
    public void handleOfflineDiagnosed(ProtocolAccountOfflineDiagnosedEvent event) {
        service.applyOfflineDiagnosed(new AccountOfflineDiagnosedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.onlineAttemptId(),
                event.previousOnlineAttemptId(),
                event.commandId(),
                event.batchId(),
                event.proxyId(),
                event.source(),
                event.from(),
                event.to(),
                event.diagnosisCode(),
                event.diagnosisClass(),
                event.rawCode(),
                event.rawReason(),
                event.recoverability(),
                event.actionTaken(),
                event.occurredAt(),
                event.workerId(),
                event.evidenceJson()));
    }
}
