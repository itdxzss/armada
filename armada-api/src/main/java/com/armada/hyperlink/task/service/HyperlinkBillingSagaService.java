package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingOperation;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingStatus;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort.Action;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort.AuditEvent;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 外部钱包调用与本地预约状态之间的可恢复 Saga。 */
@Service
public class HyperlinkBillingSagaService {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkBillingSagaService.class);
    private static final long RETRY_DELAY_MS = 30_000L;
    private final HyperlinkBillingReservationMapper billingMapper;
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkBillingConsumptionService consumptionService;
    private final HyperlinkWalletPort walletPort;
    private final HyperlinkTaskAuditPort auditPort;
    private final Clock clock;

    @Autowired
    public HyperlinkBillingSagaService(HyperlinkBillingReservationMapper billingMapper,
            HyperlinkTaskMapper taskMapper, HyperlinkBillingConsumptionService consumptionService,
            HyperlinkWalletPort walletPort, HyperlinkTaskAuditPort auditPort) {
        this(billingMapper, taskMapper, consumptionService, walletPort, auditPort,
                Clock.systemUTC());
    }

    HyperlinkBillingSagaService(HyperlinkBillingReservationMapper billingMapper,
            HyperlinkTaskMapper taskMapper, HyperlinkBillingConsumptionService consumptionService,
            HyperlinkWalletPort walletPort, HyperlinkTaskAuditPort auditPort, Clock clock) {
        this.billingMapper = billingMapper;
        this.taskMapper = taskMapper;
        this.consumptionService = consumptionService;
        this.walletPort = walletPort;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    /** 收敛首次冻结或编辑重建调整，外部结果未知时始终重放原 operationKey。 */
    public void ensureProvisionReservation(long taskId) {
        execute(taskId, () -> {
            HyperlinkBillingReservation billing = requireBilling(taskId);
            HyperlinkBillingOperation operation = operation(billing);
            if (operation == HyperlinkBillingOperation.NONE
                    && billing.getReservationStatus() == HyperlinkBillingStatus.RESERVED.code()) {
                return;
            }
            if (operation == HyperlinkBillingOperation.RESERVE) {
                reserve(taskId, billing);
                return;
            }
            if (operation == HyperlinkBillingOperation.ADJUST) {
                adjust(taskId, billing);
                return;
            }
            throw stateConflict("计费预约当前不能进入准备完成状态");
        });
    }

    /** 清理 recipient 前先收敛可能已到达钱包、但本地尚未确认的冻结或调整。 */
    public void ensureCleanupSafe(long taskId) {
        HyperlinkBillingReservation billing = requireBilling(taskId);
        HyperlinkBillingOperation pending = operation(billing);
        if (pending == HyperlinkBillingOperation.RESERVE
                || pending == HyperlinkBillingOperation.ADJUST) {
            ensureProvisionReservation(taskId);
        }
    }

    /** 在 STOP、停用或任务完成时持久化结算意图；已有待恢复动作不得被覆盖。 */
    public void beginFinalization(long taskId) {
        HyperlinkBillingReservation billing = requireBilling(taskId);
        if (billing.getReservationStatus() == HyperlinkBillingStatus.RELEASED.code()
                || operation(billing) != HyperlinkBillingOperation.NONE) {
            return;
        }
        HyperlinkTask task = requireTask(taskId);
        String key = operationKey("settle", billing, task);
        if (billingMapper.markPendingSettlement(taskId, key, clock.millis()) != 1) {
            HyperlinkBillingReservation current = requireBilling(taskId);
            if (current.getReservationStatus() != HyperlinkBillingStatus.RELEASED.code()
                    && operation(current) != HyperlinkBillingOperation.SETTLE) {
                throw stateConflict("计费结算意图登记失败");
            }
        }
    }

    /** 确认钱包从未收到冻结调用时，本地结束零金额预约，供领取阶段失败补偿。 */
    public void abandonUnstartedReservation(long taskId) {
        HyperlinkBillingReservation billing = requireBilling(taskId);
        if (billing.getReservationStatus() == HyperlinkBillingStatus.RELEASED.code()) {
            return;
        }
        if (billingMapper.abandonUnstarted(taskId, clock.millis()) != 1) {
            throw stateConflict("未发起的计费预约无法结束");
        }
        log.info("hyperlink billing abandoned before reserve taskId={}", taskId);
    }

    /**
     * 本地人数校验产生 40911 且钱包从未被调用时，结束旧 RESERVE 供清理释放受众。
     *
     * @return true 表示已结束该本地预约；false 表示不是这一类失败，仍按原幂等键恢复
     */
    public boolean abandonFailedStaleUncalledReservation(long taskId) {
        HyperlinkBillingReservation billing = requireBilling(taskId);
        String failureCode = Integer.toString(ErrorCode.HYPERLINK_QUOTE_STALE.code());
        if (!failureCode.equals(billing.getFailureCode())) {
            return false;
        }
        if (!Integer.valueOf(HyperlinkBillingStatus.FAILED.code())
                    .equals(billing.getReservationStatus())
                || !Integer.valueOf(HyperlinkBillingOperation.RESERVE.code())
                        .equals(billing.getPendingOperation())
                || billing.getExternalReservationNo() != null
                || !isZero(billing.getReservedAmount())
                || !isZero(billing.getSettledAmount())
                || !isZero(billing.getReleasedAmount())
                || billing.getSettledSendCount() == null
                || billing.getSettledSendCount() != 0
                || billing.getReservedAt() != null
                || billing.getSettledAt() != null
                || billing.getReleasedAt() != null) {
            throw stateConflict("报价过期预约事实不完整，不能本地结束");
        }
        if (billingMapper.abandonFailedStaleUncalled(
                taskId, failureCode, clock.millis()) != 1) {
            throw stateConflict("报价过期预约已被并发恢复");
        }
        log.info("hyperlink stale billing abandoned before cleanup taskId={}", taskId);
        return true;
    }

    /** 可幂等调用的最终计费：先结算唯一实际发送 recipient，再释放剩余金额。 */
    public void finalizeBilling(long taskId) {
        execute(taskId, () -> {
            HyperlinkBillingReservation billing = requireBilling(taskId);
            HyperlinkBillingOperation pending = operation(billing);
            if (pending == HyperlinkBillingOperation.RESERVE
                    || pending == HyperlinkBillingOperation.ADJUST) {
                ensurePendingProvision(taskId, billing, pending);
                billing = requireBilling(taskId);
            }
            if (billing.getReservationStatus() == HyperlinkBillingStatus.RELEASED.code()) {
                return;
            }
            if (operation(billing) == HyperlinkBillingOperation.NONE) {
                beginFinalization(taskId);
                billing = requireBilling(taskId);
            }
            if (operation(billing) == HyperlinkBillingOperation.SETTLE) {
                settle(taskId, billing);
                billing = requireBilling(taskId);
            }
            if (operation(billing) == HyperlinkBillingOperation.NONE) {
                prepareRelease(taskId, billing);
                billing = requireBilling(taskId);
            }
            if (operation(billing) == HyperlinkBillingOperation.RELEASE) {
                release(taskId, billing);
                return;
            }
            if (billing.getReservationStatus() != HyperlinkBillingStatus.RELEASED.code()) {
                throw stateConflict("计费最终收口状态非法");
            }
        });
    }

    private void reserve(long taskId, HyperlinkBillingReservation billing) {
        validateRecipientTotal(taskId, billing);
        HyperlinkTask task = requireTask(taskId);
        auditPort.requireAvailable();
        HyperlinkWalletPort.ReserveResult result = walletPort.reserve(task.getTenantId(), taskId,
                billing.getOperationIdempotencyKey(), billing.getCurrencyCode(), billing.getQuotedAmount());
        if (result == null || result.externalReservationNo() == null
                || result.reservedAmount() == null
                || result.reservedAmount().compareTo(billing.getQuotedAmount()) != 0) {
            throw billingUnavailable("钱包冻结结果不完整");
        }
        recordBillingAudit(billing, task, Action.BILLING_RESERVE);
        if (billingMapper.markReserved(taskId, result.externalReservationNo(),
                billing.getOperationIdempotencyKey(), clock.millis()) != 1) {
            requireCompleted(taskId, HyperlinkBillingStatus.RESERVED);
        }
        log.info("hyperlink billing reserved taskId={} amount={}", taskId, result.reservedAmount());
    }

    private void adjust(long taskId, HyperlinkBillingReservation billing) {
        validateRecipientTotal(taskId, billing);
        HyperlinkTask task = requireTask(taskId);
        auditPort.requireAvailable();
        HyperlinkWalletPort.AdjustmentResult result = walletPort.adjust(task.getTenantId(), taskId,
                billing.getOperationIdempotencyKey(), billing.getExternalReservationNo(),
                billing.getCurrencyCode(), billing.getQuotedAmount());
        if (result == null || result.reservedAmount() == null
                || result.reservedAmount().compareTo(billing.getQuotedAmount()) != 0) {
            throw billingUnavailable("钱包调整结果不完整");
        }
        recordBillingAudit(billing, task, Action.BILLING_ADJUST);
        if (billingMapper.markAdjusted(taskId, billing.getOperationIdempotencyKey(),
                result.reservedAmount(), clock.millis()) != 1) {
            requireCompleted(taskId, HyperlinkBillingStatus.RESERVED);
        }
        log.info("hyperlink billing adjusted taskId={} amount={}", taskId, result.reservedAmount());
    }

    private void settle(long taskId, HyperlinkBillingReservation billing) {
        HyperlinkBillingConsumptionService.Consumption consumption =
                consumptionService.snapshot(taskId, billing);
        HyperlinkTask task = requireTask(taskId);
        auditPort.requireAvailable();
        HyperlinkWalletPort.SettlementResult result = walletPort.settle(task.getTenantId(), taskId,
                billing.getOperationIdempotencyKey(), billing.getExternalReservationNo(),
                billing.getCurrencyCode(), consumption.amount(), consumption.sendCount());
        if (result == null || result.settledAmount() == null
                || result.settledAmount().compareTo(consumption.amount()) != 0
                || result.settledSendCount() != consumption.sendCount()) {
            throw billingUnavailable("钱包结算结果不完整");
        }
        recordBillingAudit(billing, task, Action.BILLING_SETTLE);
        if (billingMapper.markSettled(taskId, billing.getOperationIdempotencyKey(),
                result.settledAmount(), result.settledSendCount(), clock.millis()) != 1) {
            HyperlinkBillingReservation current = requireBilling(taskId);
            if (operation(current) != HyperlinkBillingOperation.NONE
                    || current.getSettledAmount().compareTo(consumption.amount()) != 0
                    || current.getSettledSendCount() != consumption.sendCount()) {
                throw stateConflict("计费结算本地结果未收敛");
            }
        }
        log.info("hyperlink billing settled taskId={} sendCount={} amount={}",
                taskId, result.settledSendCount(), result.settledAmount());
    }

    private void prepareRelease(long taskId, HyperlinkBillingReservation billing) {
        HyperlinkTask task = requireTask(taskId);
        String key = operationKey("release", billing, task);
        if (billingMapper.markPendingRelease(taskId, key, clock.millis()) != 1) {
            HyperlinkBillingReservation current = requireBilling(taskId);
            if (current.getReservationStatus() != HyperlinkBillingStatus.RELEASED.code()
                    && operation(current) != HyperlinkBillingOperation.RELEASE) {
                throw stateConflict("计费释放意图登记失败");
            }
        }
    }

    private void release(long taskId, HyperlinkBillingReservation billing) {
        BigDecimal releasable = billing.getReservedAmount()
                .subtract(billing.getSettledAmount()).max(BigDecimal.ZERO);
        HyperlinkTask task = requireTask(taskId);
        auditPort.requireAvailable();
        HyperlinkWalletPort.ReleaseResult result = walletPort.release(task.getTenantId(), taskId,
                billing.getOperationIdempotencyKey(), billing.getExternalReservationNo(),
                billing.getCurrencyCode(), releasable);
        if (result == null || result.releasedAmount() == null
                || result.releasedAmount().compareTo(releasable) != 0) {
            throw billingUnavailable("钱包释放结果不完整");
        }
        recordBillingAudit(billing, task, Action.BILLING_RELEASE);
        if (billingMapper.markReleased(taskId, billing.getOperationIdempotencyKey(),
                result.releasedAmount(), clock.millis()) != 1) {
            requireCompleted(taskId, HyperlinkBillingStatus.RELEASED);
        }
        log.info("hyperlink billing released taskId={} amount={}", taskId, result.releasedAmount());
    }

    private void ensurePendingProvision(long taskId, HyperlinkBillingReservation billing,
            HyperlinkBillingOperation operation) {
        if (operation == HyperlinkBillingOperation.RESERVE) {
            reserve(taskId, billing);
        } else {
            adjust(taskId, billing);
        }
    }

    private void validateRecipientTotal(long taskId, HyperlinkBillingReservation billing) {
        if (consumptionService.recipientCount(taskId) != billing.getQuotedRecipientCount()) {
            throw new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE,
                    "实际领取人数与报价人数不一致");
        }
    }

    private void requireCompleted(long taskId, HyperlinkBillingStatus expected) {
        HyperlinkBillingReservation current = requireBilling(taskId);
        if (current.getReservationStatus() != expected.code()
                || operation(current) != HyperlinkBillingOperation.NONE) {
            throw stateConflict("计费 Saga 本地状态已变化");
        }
    }

    private HyperlinkBillingReservation requireBilling(long taskId) {
        HyperlinkBillingReservation billing = billingMapper.selectByTaskId(taskId);
        if (billing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务计费预约不存在");
        }
        return billing;
    }

    private HyperlinkTask requireTask(long taskId) {
        HyperlinkTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
        return task;
    }

    private HyperlinkBillingOperation operation(HyperlinkBillingReservation billing) {
        try {
            return HyperlinkBillingOperation.fromCode(billing.getPendingOperation());
        } catch (IllegalArgumentException exception) {
            throw stateConflict("计费待恢复操作非法");
        }
    }

    private String operationKey(String action, HyperlinkBillingReservation billing,
            HyperlinkTask task) {
        return HyperlinkBillingOperationKeys.create(action, billing.getExternalReservationNo(),
                task.getId(), task.getVersion());
    }

    private void recordBillingAudit(HyperlinkBillingReservation billing, HyperlinkTask task,
            Action action) {
        auditPort.record(new AuditEvent("hyperlink-billing:"
                + billing.getOperationIdempotencyKey(), action, task.getTenantId(), null,
                task.getId(), clock.millis()));
    }

    private void execute(long taskId, Runnable action) {
        try {
            action.run();
        } catch (BusinessException exception) {
            markFailure(taskId, String.valueOf(exception.getCode()), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            markFailure(taskId, "BILLING_OPERATION_FAILED", exception.getMessage());
            throw billingUnavailable("钱包操作暂未收敛");
        }
    }

    private void markFailure(long taskId, String code, String reason) {
        long now = clock.millis();
        try {
            billingMapper.markFailed(taskId, safe(code, 64), safe(reason, 255),
                    now + RETRY_DELAY_MS, now);
        } catch (RuntimeException persistenceFailure) {
            log.warn("hyperlink billing failure state write failed taskId={}", taskId,
                    persistenceFailure);
        }
        log.warn("hyperlink billing operation failed taskId={} code={}", taskId, safe(code, 64));
    }

    private String safe(String value, int maxLength) {
        String normalized = value == null ? "计费操作失败" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private boolean isZero(BigDecimal amount) {
        return amount != null && amount.signum() == 0;
    }

    private BusinessException stateConflict(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, message);
    }

    private BusinessException billingUnavailable(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE, message);
    }
}
