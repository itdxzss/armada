package com.armada.hyperlink.task.service;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** hyperlink send-result/ACK 唯一路由与 recipient 单调回写。 */
@Service
public class HyperlinkProtocolResultService
        implements ProtocolMessageSendResultReportedSink, ProtocolMessageAckSink {
    private static final String SOURCE = "hyperlink_task";
    private static final Set<String> RECOVERABLE_RESTRICTION_CODES =
            Set.of("ACCOUNT_REACHOUT_RESTRICTED");
    private static final Set<String> BANNED_CODES = Set.of("ACCOUNT_BANNED");
    private static final Set<String> INVALID_CODES = Set.of(
            "DEVICE_DELETED", "DEVICE_REMOVED", "LOGGED_OUT", "PRIMARY_DEVICE_LOGGED_OUT",
            "PRIMARY_DEVICE_WAS_LOGGED_OUT", "ACCOUNT_UNBOUND", "ACCOUNT_INVALID");
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkRecipientStateMachine stateMachine;
    private final DataPackageRecipientClaimService dataPackageRecipientClaimService;
    private final HyperlinkAccountDispatchGuard dispatchGuard;
    private final AccountOperationRestrictionService operationRestrictionService;

    public HyperlinkProtocolResultService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkRecipientStateMachine stateMachine,
            DataPackageRecipientClaimService dataPackageRecipientClaimService,
            HyperlinkAccountDispatchGuard dispatchGuard,
            AccountOperationRestrictionService operationRestrictionService) {
        this.recipientMapper = recipientMapper;
        this.usageMapper = usageMapper;
        this.stateMachine = stateMachine;
        this.dataPackageRecipientClaimService = dataPackageRecipientClaimService;
        this.dispatchGuard = dispatchGuard;
        this.operationRestrictionService = operationRestrictionService;
    }

    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE.equals(event.source());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            HyperlinkTaskRecipient recipient = resolveCurrentRecipient(
                    event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                    event.commandId());
            if (recipient == null) { return; }
            requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
            long now = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
            String outcome = event.outcome() == null ? null
                    : event.outcome().toUpperCase(Locale.ROOT);
            if ("UNKNOWN".equals(outcome)) {
                reconcileIfStillSending(event, recipient, now);
                return;
            }
            if ("FAILED".equals(outcome) && Boolean.FALSE.equals(event.terminal())) {
                reconcileIfStillSending(event, recipient, now);
                return;
            }
            boolean successful = outcome == null ? event.success() : "SUCCESS".equals(outcome);
            if (outcome != null && !successful && !"FAILED".equals(outcome)) {
                throw new BusinessException(ErrorCode.VALIDATION, "超链发送 outcome 非法");
            }
            int status = successful ? HyperlinkRecipientStatus.SUCCESS.code()
                    : failureStatus(event.reasonCode()).code();
            recipient.setSendStatus(status);
            recipient.setProtocolMessageId(event.messageId());
            recipient.setFailCode(safe(event.reasonCode(), 64));
            recipient.setFailReason(safe(event.reasonMessage(), 255));
            recipient.setUpdatedAt(now);
            HyperlinkTaskAccountUsage usage = lockUsage(recipient);
            if (!successful && isRecoverableRestriction(event.reasonCode())
                    && requeueRestrictedRecipient(
                    recipient, usage, event.reasonCode(), event.reasonMessage(), now)) {
                return;
            }
            int updated = recipientMapper.applyResult(recipient);
            if (updated == 1) {
                if (usage != null) {
                    invalidateUsageIfNeeded(usage, event.reasonCode(), event.reasonMessage(), now);
                    usageMapper.completeSlot(usage.getId(), successful, now);
                }
                advanceDataFact(recipient, status, now);
                releaseGuardAfterCommit(recipient);
            }
        } finally {
            restore(previous);
        }
    }

    private void reconcileIfStillSending(ProtocolMessageSendResultReportedEvent event,
            HyperlinkTaskRecipient observedRecipient, long now) {
        lockUsage(observedRecipient);
        HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                observedRecipient.getCommandId());
        requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
        if (recipient.getSendStatus() != HyperlinkRecipientStatus.SENDING.code()) {
            return;
        }
        renewGuard(recipient);
        recipientMapper.scheduleReconciliation(recipient.getCommandId(), now + 30_000L, now);
    }

    @Override
    public boolean supports(ProtocolMessageAckEvent event) {
        return event != null && SOURCE.equals(event.source());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAck(ProtocolMessageAckEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            HyperlinkTaskRecipient observedRecipient = event.commandId() == null ? null
                    : resolveCurrentRecipient(
                    event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                    event.commandId());
            if (observedRecipient == null && event.commandId() != null) { return; }
            if (observedRecipient == null && event.accountId() != null && event.protocolId() != null) {
                observedRecipient = recipientMapper.selectByProtocolMessage(
                        event.accountId(), event.protocolId(), event.messageId());
            }
            requireIdentity(observedRecipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
            HyperlinkRecipientStatus incoming = "FAILED".equals(event.ackStatus())
                    ? failureStatus(event.reasonCode()) : ackStatus(event.ackStatus());
            HyperlinkTaskAccountUsage usage = lockUsage(observedRecipient);
            HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                    event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                    observedRecipient.getCommandId());
            requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
            HyperlinkRecipientStatus current = HyperlinkRecipientStatus.fromCode(
                    recipient.getSendStatus());
            HyperlinkRecipientStatus next = stateMachine.advance(current, incoming);
            if (next == current) { return; }
            long now = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
            if (current == HyperlinkRecipientStatus.SENDING
                    && incoming.terminalFailure()
                    && isRecoverableRestriction(event.reasonCode())
                    && requeueRestrictedRecipient(
                    recipient, usage, event.reasonCode(), event.reasonMessage(), now)) {
                return;
            }
            recipient.setSendStatus(next.code());
            recipient.setProtocolMessageId(event.messageId());
            recipient.setFailCode(safe(event.reasonCode(), 64));
            recipient.setFailReason(safe(event.reasonMessage(), 255));
            recipient.setUpdatedAt(now);
            if (recipientMapper.advanceAck(recipient, current.code()) == 1) {
                if (usage != null) {
                    if (incoming.terminalFailure()) {
                        invalidateUsageIfNeeded(usage, event.reasonCode(), event.reasonMessage(), now);
                        usageMapper.completeSlot(usage.getId(), false, now);
                    } else if (current == HyperlinkRecipientStatus.SENDING
                            && next.rank() >= HyperlinkRecipientStatus.SUCCESS.rank()) {
                        usageMapper.completeSlot(usage.getId(), true, now);
                    }
                }
                advanceDataFact(recipient, next.code(), now);
                releaseGuardAfterCommit(recipient);
            }
        } finally {
            restore(previous);
        }
    }

    private HyperlinkTaskAccountUsage lockUsage(HyperlinkTaskRecipient recipient) {
        if (recipient.getAccountId() == null) { return null; }
        return usageMapper.selectByTaskAndAccountForUpdate(
                recipient.getHyperlinkTaskId(), recipient.getAccountId());
    }

    private boolean requeueRestrictedRecipient(HyperlinkTaskRecipient recipient,
            HyperlinkTaskAccountUsage usage, String reasonCode, String reason, long occurredAt) {
        if (recipient.getAccountId() == null || usage == null) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "账号受限换号缺少账号占用事实");
        }
        long processedAt = System.currentTimeMillis();
        operationRestrictionService.restrictMessageSending(
                recipient.getAccountId(), reasonCode, occurredAt, processedAt);
        if (recipientMapper.requeueAfterAccountRestriction(
                recipient.getId(), recipient.getCommandId(), processedAt) != 1) {
            return false;
        }
        usageMapper.completeSlot(usage.getId(), false, processedAt);
        if (usageMapper.markOperationRestricted(
                usage.getId(), HyperlinkTaskAccountUsageStatus.OPERATION_RESTRICTED.code(),
                reasonCode, safe(reason, 255), processedAt) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "账号受限后的当前任务账号退出失败");
        }
        releaseGuardAfterCommit(recipient);
        return true;
    }

    private boolean isRecoverableRestriction(String reasonCode) {
        String normalized = reasonCode == null ? ""
                : reasonCode.trim().toUpperCase(Locale.ROOT);
        return RECOVERABLE_RESTRICTION_CODES.contains(normalized);
    }

    private HyperlinkTaskRecipient resolveCurrentRecipient(
            Long tenantId, Long taskId, Long recipientId, String commandId) {
        HyperlinkTaskRecipient recipient = recipientMapper.selectByCommandId(commandId);
        if (recipient != null) {
            return recipient;
        }
        if (tenantId == null || taskId == null || recipientId == null) {
            return null;
        }
        HyperlinkTaskRecipient current = recipientMapper.selectCurrentByIdentity(
                tenantId, taskId, recipientId);
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链 recipient 不存在");
        }
        String commandPrefix = "hl:" + tenantId + ":" + taskId + ":" + recipientId;
        if (commandId != null
                && (commandId.equals(commandPrefix)
                || commandId.startsWith(commandPrefix + ":"))) {
            return null;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "超链结果 commandId 不属于该 recipient");
    }

    private void invalidateUsageIfNeeded(HyperlinkTaskAccountUsage usage, String code,
            String reason, long now) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        int status;
        if (BANNED_CODES.contains(normalized)) {
            status = HyperlinkTaskAccountUsageStatus.BANNED.code();
        } else if (INVALID_CODES.contains(normalized)) {
            status = HyperlinkTaskAccountUsageStatus.INVALID.code();
        } else {
            return;
        }
        usageMapper.markInvalid(usage.getId(), status, safe(code, 64), safe(reason, 255), now);
    }

    private void advanceDataFact(HyperlinkTaskRecipient recipient, int status, long now) {
        DataPackagePoolStatus poolStatus = switch (HyperlinkRecipientStatus.fromCode(status)) {
            case SUCCESS -> DataPackagePoolStatus.SENT;
            case DELIVERED, READ -> DataPackagePoolStatus.DELIVERED;
            case FAILED -> DataPackagePoolStatus.RETRYABLE_FAILED;
            case UNREGISTERED -> DataPackagePoolStatus.UNREGISTERED;
            default -> null;
        };
        if (poolStatus != null) {
            dataPackageRecipientClaimService.advanceDeliveryFact(
                    recipient.getHyperlinkTaskId(), recipient.getDataPackageId(),
                    recipient.getDataPackageGeneration(), recipient.getRecipientPhoneSnapshot(),
                    poolStatus, now);
        }
    }

    private void renewGuard(HyperlinkTaskRecipient recipient) {
        requireGuardIdentity(recipient);
        dispatchGuard.renew(recipient.getAccountId(), recipient.getCommandId());
    }

    private void releaseGuardAfterCommit(HyperlinkTaskRecipient recipient) {
        requireGuardIdentity(recipient);
        dispatchGuard.releaseAfterCommit(recipient.getAccountId(), recipient.getCommandId(),
                recipient.getHyperlinkTaskId(), recipient.getId());
    }

    private void requireGuardIdentity(HyperlinkTaskRecipient recipient) {
        if (recipient.getAccountId() == null || recipient.getCommandId() == null
                || recipient.getCommandId().isBlank()) {
            throw new BusinessException(ErrorCode.HYPERLINK_DISPATCH_GUARD_UNAVAILABLE);
        }
    }

    private void requireIdentity(HyperlinkTaskRecipient recipient, Long taskId, Long recipientId) {
        if (recipient == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链 recipient 不存在");
        }
        if (!Objects.equals(taskId, recipient.getHyperlinkTaskId())
                || !Objects.equals(recipientId, recipient.getId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "超链结果关联不一致");
        }
    }

    private HyperlinkRecipientStatus failureStatus(String reasonCode) {
        String value = reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT);
        return value.contains("404") || value.contains("UNREGISTERED")
                ? HyperlinkRecipientStatus.UNREGISTERED : HyperlinkRecipientStatus.FAILED;
    }

    private HyperlinkRecipientStatus ackStatus(String value) {
        return switch (value) {
            case "SUCCESS" -> HyperlinkRecipientStatus.SUCCESS;
            case "DELIVERED" -> HyperlinkRecipientStatus.DELIVERED;
            case "READ" -> HyperlinkRecipientStatus.READ;
            case "FAILED" -> HyperlinkRecipientStatus.FAILED;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "超链 ACK 状态非法");
        };
    }

    private String safe(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void restore(Long previous) {
        if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
    }
}
