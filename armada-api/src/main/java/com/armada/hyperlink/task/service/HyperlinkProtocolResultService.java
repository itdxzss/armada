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
    private static final String LEGACY_UNKNOWN_RESULT_CODE = "SEND_RESULT_UNKNOWN";
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
    private final HyperlinkMetricsProjectionService metrics;

    public HyperlinkProtocolResultService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkRecipientStateMachine stateMachine,
            DataPackageRecipientClaimService dataPackageRecipientClaimService,
            HyperlinkAccountDispatchGuard dispatchGuard,
            AccountOperationRestrictionService operationRestrictionService, HyperlinkMetricsProjectionService metrics) {
        this.recipientMapper = recipientMapper;
        this.usageMapper = usageMapper;
        this.stateMachine = stateMachine;
        this.dataPackageRecipientClaimService = dataPackageRecipientClaimService;
        this.dispatchGuard = dispatchGuard;
        this.operationRestrictionService = operationRestrictionService;
        this.metrics = metrics;
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
            // Android 旧事件没有 outcome/terminal，仅通过失败码表达未知，沿用原命令对账。
            if ("UNKNOWN".equals(outcome)
                    || (outcome == null && !event.success()
                    && LEGACY_UNKNOWN_RESULT_CODE.equals(event.reasonCode()))) {
                reconcileIfStillSending(event, recipient, now);
                return;
            }
            if ("FAILED".equals(outcome) && Boolean.FALSE.equals(event.terminal())) {
                reconcileIfStillSending(event, recipient, now);
                return;
            }
            boolean successful = outcome == null ? event.success() : "SUCCESS".equals(outcome);
            if (outcome != null && !successful && !"FAILED".equals(outcome) && !"NOT_SENT".equals(outcome)) {
                throw new BusinessException(ErrorCode.VALIDATION, "超链发送 outcome 非法");
            }
            if (!successful && !HyperlinkSendFailurePolicy.targetFailure(event.reasonCode())) {
                if (HyperlinkSendFailurePolicy.definitelyNotSent(outcome, event.reasonCode())) {
                    requeueSystemFailure(recipient, event.reasonCode(), event.reasonMessage(), now, event.messageId());
                } else {
                    reconcileIfStillSending(event, recipient, now);
                }
                return;
            }
            int status = successful ? HyperlinkRecipientStatus.SUCCESS.code()
                    : failureStatus(event.reasonCode()).code();
            HyperlinkTaskAccountUsage usage = lockUsage(recipient);
            recipient = recipientMapper.selectByIdentityForUpdate(event.tenantId(),
                    event.hyperlinkTaskId(), event.hyperlinkRecipientId(), event.commandId());
            requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
            if (successful && correctTimedOutSuccess(recipient, usage,
                    HyperlinkRecipientStatus.SUCCESS, event.messageId(), now)) { return; }
            recipient.setSendStatus(status);
            recipient.setProtocolMessageId(event.messageId());
            recipient.setFailCode(safe(event.reasonCode(), 64));
            recipient.setFailReason(safe(event.reasonMessage(), 255));
            recipient.setUpdatedAt(now);
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
        HyperlinkTaskAccountUsage usage = lockUsage(observedRecipient);
        HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                observedRecipient.getCommandId());
        requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
        if (recipient.getSendStatus() != HyperlinkRecipientStatus.SENDING.code()) {
            return;
        }
        if (usage != null) {
            invalidateUsageIfNeeded(usage, event.reasonCode(), event.reasonMessage(), now);
        }
        renewGuard(recipient);
        recipient.setProtocolMessageId(event.messageId());
        recipient.setFailCode(LEGACY_UNKNOWN_RESULT_CODE);
        recipient.setFailReason(safe(event.reasonMessage(), 255));
        recipient.setUpdatedAt(now);
        recipientMapper.rememberUnknownResult(recipient);
        recipientMapper.scheduleReconciliation(recipient.getCommandId(), now + 30_000L, now);
    }

    private void requeueSystemFailure(HyperlinkTaskRecipient observed, String code, String reason,
            long occurredAt, String messageId) {
        metrics.lockRetryScope(observed);
        HyperlinkTaskAccountUsage usage = lockUsage(observed);
        HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                observed.getTenantId(), observed.getHyperlinkTaskId(), observed.getId(), observed.getCommandId());
        if (recipient == null || recipient.getSendStatus() != HyperlinkRecipientStatus.SENDING.code()) { return; }
        if (usage == null || recipient.getAccountId() == null) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, "恢复发送缺少账号占用事实");
        }
        long now = System.currentTimeMillis();
        recipient.setFailCode(safe(code, 64));
        recipient.setFailReason(safe(reason, 255));
        recipient.setUpdatedAt(now);
        if (HyperlinkSendFailurePolicy.ACK_REJECTED_463.equals(code)) {
            recipient.setProtocolMessageId(messageId);
            recipientMapper.rememberRejectedSender(recipient);
            recipient.setNextDispatchAt(HyperlinkSendFailurePolicy.nextRetryAt(
                    recipient.getDispatchAttempt(), code, now));
            metrics.requeueSystemFailure(recipient);
            usageMapper.completeSlot(usage.getId(), false, now);
            releaseGuardAfterCommit(recipient);
            return;
        }
        recipient.setNextDispatchAt(HyperlinkSendFailurePolicy.nextRetryAt(recipient.getDispatchAttempt(), code, now));
        metrics.requeueSystemFailure(recipient);
        usageMapper.completeSlot(usage.getId(), false, now);
        if (isRecoverableRestriction(code)) {
            operationRestrictionService.restrictMessageSending(recipient.getAccountId(), code, occurredAt, now);
        }
        if (code != null && (BANNED_CODES.contains(code) || INVALID_CODES.contains(code))) {
            invalidateUsageIfNeeded(usage, code, reason, now);
        } else if (HyperlinkSendFailurePolicy.unavailableAccount(code)) {
            // 只退出当前任务；账号全局状态仍由原账号事件维护。
            usageMapper.markOperationRestricted(usage.getId(),
                    HyperlinkTaskAccountUsageStatus.OPERATION_RESTRICTED.code(), code, safe(reason, 255), now);
        } else {
            long previousNext = usage.getNextSendAt() == null ? 0L : usage.getNextSendAt();
            usageMapper.scheduleNextSend(usage.getId(), Math.max(previousNext, now + 60_000L), now);
        }
        releaseGuardAfterCommit(recipient);
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
            if (incoming.terminalFailure() && !HyperlinkSendFailurePolicy.targetFailure(event.reasonCode())) {
                long occurredAt = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
                requeueSystemFailure(observedRecipient, event.reasonCode(), event.reasonMessage(), occurredAt, event.messageId());
                return;
            }
            HyperlinkTaskAccountUsage usage = lockUsage(observedRecipient);
            HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                    event.tenantId(), event.hyperlinkTaskId(), event.hyperlinkRecipientId(),
                    observedRecipient.getCommandId());
            requireIdentity(recipient, event.hyperlinkTaskId(), event.hyperlinkRecipientId());
            HyperlinkRecipientStatus current = HyperlinkRecipientStatus.fromCode(
                    recipient.getSendStatus());
            long now = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
            if (!incoming.terminalFailure()
                    && correctTimedOutSuccess(recipient, usage, incoming, event.messageId(), now)) { return; }
            HyperlinkRecipientStatus next = stateMachine.advance(current, incoming);
            if (next == current) { return; }
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

    /** 超时已结束占用；迟到回执只修正事实，不再次完成槽位或释放 guard。 */
    private boolean correctTimedOutSuccess(HyperlinkTaskRecipient recipient,
            HyperlinkTaskAccountUsage usage, HyperlinkRecipientStatus incoming,
            String messageId, long now) {
        if (recipient.getSendStatus() != HyperlinkRecipientStatus.FAILED.code()
                || !HyperlinkRecipientStatus.isResultTimeout(recipient.getFailCode())
                || incoming.rank() < HyperlinkRecipientStatus.SUCCESS.rank()) { return false; }
        recipient.setSendStatus(incoming.code());
        recipient.setProtocolMessageId(messageId);
        recipient.setUpdatedAt(now);
        if (recipientMapper.correctTimedOutResult(recipient) == 1) {
            if (usage != null) { usageMapper.recordLateSuccess(usage.getId(), now); }
            advanceDataFact(recipient, incoming.code(), now);
        }
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
        return HyperlinkSendFailurePolicy.unregisteredTarget(reasonCode)
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
