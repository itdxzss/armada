package com.armada.account.service.impl;

import com.armada.account.service.AccountTypeDetectedEvent;
import com.armada.account.service.AccountTypeVerificationService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountTypeDetectedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountTypeDetectedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 协议账号类型检测事件到 account 域服务的 adapter。 */
@Service
public class AccountTypeDetectedSinkAdapter implements ProtocolAccountTypeDetectedSink {

    private final AccountTypeVerificationService service;

    /**
     * 创建协议类型检测 adapter。
     *
     * @param service 账号类型校验服务
     */
    public AccountTypeDetectedSinkAdapter(AccountTypeVerificationService service) {
        this.service = service;
    }

    @Override
    public void handleTypeDetected(ProtocolAccountTypeDetectedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            service.applyDetected(new AccountTypeDetectedEvent(
                    event.tenantId(),
                    event.accountId(),
                    event.protocolAccountId(),
                    event.onlineAttemptId(),
                    event.commandId(),
                    event.protocolBackend(),
                    event.credentialVersion(),
                    event.declaredAccountType(),
                    event.detectedAccountType(),
                    event.verificationLevel(),
                    event.source(),
                    event.detectedAt(),
                    event.eventId()));
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
