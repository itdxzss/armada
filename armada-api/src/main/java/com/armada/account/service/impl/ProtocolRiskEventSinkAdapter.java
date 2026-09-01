package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountRestrictedEvent;
import com.armada.platform.protocol.risk.ProtocolRiskResultMetadata;
import com.armada.platform.protocol.risk.ProtocolRiskEventSink;
import com.armada.platform.protocol.risk.mapper.ProtocolRiskEventMapper;
import com.armada.platform.protocol.risk.model.ProtocolRiskEvent;
import com.armada.platform.protocol.risk.model.ProtocolRiskSignal;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.trace.TraceContext;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 将三类协议风控信号保存为不可覆盖历史，并只投影账号级外联限制。 */
@Service
public class ProtocolRiskEventSinkAdapter implements ProtocolRiskEventSink {

    private final ProtocolRiskEventMapper eventMapper;
    private final AccountMapper accountMapper;
    private final AccountOperationRestrictionService restrictionService;

    public ProtocolRiskEventSinkAdapter(
            ProtocolRiskEventMapper eventMapper,
            AccountMapper accountMapper,
            AccountOperationRestrictionService restrictionService) {
        this.eventMapper = eventMapper;
        this.accountMapper = accountMapper;
        this.restrictionService = restrictionService;
    }

    @Override
    public void handleResult(ProtocolRiskResultMetadata metadata) {
        ProtocolRiskSignal.fromCode(metadata.reasonCode()).ifPresent(signal -> withinTenant(
                metadata.event().tenantId(), () -> {
                    long receivedAt = System.currentTimeMillis();
                    Account account = resolveCanonicalAccount(
                            metadata.account().protocolAccountId(), metadata.account().accountId());
                    ProtocolRiskEvent row = base(
                            metadata.event().eventId(), metadata.event().tenantId(), signal,
                            metadata.event().source(), metadata.event().occurredAt(), receivedAt,
                            metadata.account().protocolAccountId(), metadata.event().workerId());
                    row.setOperationType(normalize(metadata.event().operationType(), 64));
                    applyAccount(row, account);
                    String reportedBackend = protocolBackend(
                            metadata.account().protocolBackend());
                    if (reportedBackend != null) {
                        row.setProtocolBackend(reportedBackend);
                    }
                    ProtocolRiskResultMetadata.Correlation correlation = metadata.correlation();
                    row.setBusinessType(safe(correlation.businessType(), 64));
                    row.setBusinessId(correlation.businessId());
                    row.setBusinessItemId(correlation.businessItemId());
                    row.setGroupBusinessId(correlation.groupBusinessId());
                    row.setCommandId(safe(correlation.commandId(), 191));
                    row.setMessageId(safe(correlation.messageId(), 191));
                    row.setTargetKind(normalize(correlation.targetKind(), 16));
                    if (isGroupJid(correlation.groupJid())) {
                        row.setChatJid(safe(correlation.groupJid(), 191));
                    }
                    row.setRawCode(safe(correlation.rawCode(), 64));
                    row.setReasonMessage(safe(metadata.reasonMessage(), 255));
                    eventMapper.insertIdempotent(row);
                    if (signal == ProtocolRiskSignal.ACCOUNT_REACHOUT_RESTRICTED
                            && row.getAccountId() != null) {
                        restrictionService.restrictMessageSending(
                                row.getAccountId(), signal.name(), row.getOccurredAt(), receivedAt);
                    }
                }));
    }

    @Override
    public void handleAccountRestricted(ProtocolAccountRestrictedEvent event) {
        ProtocolRiskSignal signal = ProtocolRiskSignal.ACCOUNT_REACHOUT_RESTRICTED;
        withinTenant(event.tenantId(), () -> {
            long receivedAt = System.currentTimeMillis();
            Account account = resolveCanonicalAccount(
                    event.protocolAccountId(), event.accountId());
            ProtocolRiskEvent row = base(
                    event.eventId(), event.tenantId(), signal, "account.restricted",
                    event.occurredAt(), receivedAt, event.protocolAccountId(), event.workerId());
            row.setOperationType("ACCOUNT_REACHOUT");
            applyAccount(row, account);
            String reportedBackend = protocolBackend(event.protocolBackend());
            if (reportedBackend != null) {
                row.setProtocolBackend(reportedBackend);
            }
            row.setRawCode(safe(event.rawCode(), 64));
            row.setReasonMessage(safe(event.reasonMessage(), 255));
            row.setIsActive(event.active());
            row.setEnforcementType(normalize(event.enforcementType(), 64));
            row.setRestrictedUntil(event.restrictedUntil());
            eventMapper.insertIdempotent(row);
            if (event.active()) {
                restrictionService.restrictPlatformMessageSending(
                        account.getId(), signal.name(), row.getOccurredAt(),
                        event.restrictedUntil(), receivedAt);
            } else {
                restrictionService.clearPlatformMessageSending(
                        account.getId(), row.getOccurredAt(), receivedAt);
            }
        });
    }

    private ProtocolRiskEvent base(String eventId, Long tenantId, ProtocolRiskSignal signal,
            String source, Long occurredAt, long receivedAt, String protocolAccountId,
            String workerId) {
        ProtocolRiskEvent row = new ProtocolRiskEvent();
        row.setTenantId(tenantId);
        row.setEventId(safe(eventId == null || eventId.isBlank()
                ? source + ":" + protocolAccountId + ":" + signal.name() + ":"
                        + (occurredAt == null ? receivedAt : occurredAt)
                : eventId, 191));
        row.setSignalCode(signal.name());
        row.setScopeType(signal.scopeType());
        row.setSource(source);
        row.setProtocolAccountId(safe(protocolAccountId, 191));
        row.setTraceId(safe(TraceContext.current().orElse(null), 64));
        row.setWorkerId(safe(workerId, 128));
        row.setOccurredAt(occurredAt == null || occurredAt <= 0 ? receivedAt : occurredAt);
        row.setReceivedAt(receivedAt);
        return row;
    }

    private Account resolveCanonicalAccount(String protocolAccountId, Long declaredAccountId) {
        if (protocolAccountId == null || protocolAccountId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议风控事件缺少 protocolAccountId");
        }
        Account account = accountMapper.selectActiveByProtocolAccountId(protocolAccountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议风控事件账号绑定不存在");
        }
        if (declaredAccountId != null && !declaredAccountId.equals(account.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议风控事件账号绑定不一致");
        }
        return account;
    }

    private static void applyAccount(ProtocolRiskEvent row, Account account) {
        if (account == null) { return; }
        row.setAccountId(account.getId());
        row.setProtocolBackend(protocolBackend(account.getProtocolId()));
    }

    private static String normalize(String value, int max) {
        return value == null ? null : safe(value.trim().toUpperCase(Locale.ROOT), max);
    }

    private static String protocolBackend(String value) {
        String normalized = normalize(value, 32);
        return "WEB".equals(normalized) || "ANDROID".equals(normalized)
                ? normalized : null;
    }

    private static String safe(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isGroupJid(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).endsWith("@g.us");
    }

    private static void withinTenant(Long tenantId, Runnable action) {
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议风控事件缺少 tenantId");
        }
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }
}
