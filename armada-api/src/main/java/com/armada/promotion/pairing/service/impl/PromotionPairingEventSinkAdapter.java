package com.armada.promotion.pairing.service.impl;

import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEvent;
import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEventSink;
import com.armada.platform.protocol.model.result.PairingCredentialExport;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionPairingStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 协议配对事件在推广业务域的处理适配器。 */
@Service
public class PromotionPairingEventSinkAdapter implements ProtocolPairingEventSink {

    private static final Logger log = LoggerFactory.getLogger(PromotionPairingEventSinkAdapter.class);
    private static final String ERROR_PROTOCOL_FAILED = "PROTOCOL_PAIRING_FAILED";

    private final PromotionPairingSessionMapper sessionMapper;
    private final PairingLoginPort pairingLoginPort;
    private final PromotionPairingCompletionService completionService;

    public PromotionPairingEventSinkAdapter(PromotionPairingSessionMapper sessionMapper,
                                            PairingLoginPort pairingLoginPort,
                                            PromotionPairingCompletionService completionService) {
        this.sessionMapper = sessionMapper;
        this.pairingLoginPort = pairingLoginPort;
        this.completionService = completionService;
    }

    @Override
    public void handle(ProtocolPairingEvent event) {
        // 推广专用协议账号每次尝试唯一，直接使用公共事件已有的 accountId 精确定位，不扩展事件契约。
        PromotionPairingSession session =
                sessionMapper.selectActiveByProtocolAccountId(event.protocolAccountId());
        if (session == null) {
            // 已成功/失败的重复事件无需再次落库，按幂等消息跳过。
            log.info("协议配对事件未命中活动会话，按幂等跳过 eventId={} eventType={}",
                    event.eventId(), event.eventType());
            return;
        }
        Long ownerUserId = requireOwner(session);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(session.getTenantId());
            try (DataScopeContext.Scope ignored =
                         DataScopeContext.open(DataScope.self(ownerUserId))) {
                if (ProtocolPairingEvent.EVENT_CODE_GENERATED.equals(event.eventType())) {
                    sessionMapper.markCodeGenerated(
                            session.getId(), session.getTenantId(), event.protocolAccountId(),
                            event.pairingCode(), event.expiresAt(), event.occurredAt());
                    return;
                }
                if (ProtocolPairingEvent.EVENT_FAILED.equals(event.eventType())) {
                    completionService.terminate(
                            session,
                            PromotionPairingStatus.FAILED,
                            ERROR_PROTOCOL_FAILED,
                            "WhatsApp 配对失败，请重试",
                            event.occurredAt());
                    return;
                }
                validateCompleted(session, event);
                // 外部 HTTP 导出必须在本地事务之外执行；失败时抛出，让 Kafka 统一重试。
                PairingCredentialExport credential =
                        pairingLoginPort.exportCredential(event.protocolAccountId());
                validateCredential(event.protocolAccountId(), credential);
                completionService.complete(session.getId(), session.getTenantId(), event, credential);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static Long requireOwner(PromotionPairingSession session) {
        if (session.getOwnerUserId() == null) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "历史无归属推广配对会话不能继续执行");
        }
        return session.getOwnerUserId();
    }

    private static void validateCompleted(PromotionPairingSession session, ProtocolPairingEvent event) {
        if (!ProtocolPairingEvent.EVENT_COMPLETED.equals(event.eventType())
                || !session.getProtocolAccountId().equals(event.protocolAccountId())
                || !session.getPhone().equals(normalizeProtocolPhone(event.phone()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议配对完成事件与会话不一致");
        }
    }

    /** Baileys 的 me.id 可能携带多设备后缀（如 phone:device），账号主键只使用纯手机号部分。 */
    private static String normalizeProtocolPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim();
        int deviceSeparator = normalized.indexOf(':');
        return deviceSeparator > 0 ? normalized.substring(0, deviceSeparator) : normalized;
    }

    private static void validateCredential(String protocolAccountId, PairingCredentialExport credential) {
        if (credential == null || !protocolAccountId.equals(credential.protocolAccountId())
                || credential.credentialJson() == null || credential.credentialJson().isBlank()) {
            throw new IllegalStateException("协议层未返回完整配对凭据");
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
