package com.armada.hyperlink.task.service;

import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingOperation;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 建立或重置 claim 与单任务 billing Saga 本地事实。 */
@Service
public class HyperlinkProvisionFactService {
    private static final int CLAIM_OWNED = 3;
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkBillingReservationMapper billingMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final DataPackageRecipientClaimService dataPackageService;
    private final ObjectMapper objectMapper;

    public HyperlinkProvisionFactService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkBillingReservationMapper billingMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            DataPackageRecipientClaimService dataPackageService, ObjectMapper objectMapper) {
        this.claimMapper = claimMapper;
        this.billingMapper = billingMapper;
        this.recipientMapper = recipientMapper;
        this.dataPackageService = dataPackageService;
        this.objectMapper = objectMapper;
    }

    public void prepare(HyperlinkTask task, HyperlinkQuoteTokenService.QuoteClaims claims, long now) {
        if (claims == null || !dataPackageService.isCurrentGeneration(
                claims.dataPackageId(), claims.dataPackageGeneration())) {
            throw new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE, "数据包代次已变化");
        }
        HyperlinkTaskRecipientClaim claim = claim(task.getId(), claims, now);
        HyperlinkTaskRecipientClaim existingClaim = claimMapper.selectByTaskId(
                task.getTenantId(), task.getId());
        if (existingClaim == null) {
            claimMapper.insert(claim);
        } else if (claimMapper.resetForClaim(claim, existingClaim.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "recipient claim 尚未释放完成");
        }

        HyperlinkBillingReservation existingBilling = billingMapper.selectByTaskId(task.getId());
        HyperlinkBillingReservation billing = billing(task, claims, existingBilling, now);
        if (existingBilling == null) {
            billingMapper.insert(billing);
        } else if (existingBilling.getReservationStatus() == HyperlinkBillingStatus.RELEASED.code()
                && billingMapper.resetForReserve(billing, existingBilling.getVersion()) == 1) {
            return;
        } else if (canAdjust(existingBilling, billing)
                && billingMapper.resetForAdjustment(billing, existingBilling.getVersion()) == 1) {
            return;
        } else {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "旧计费预约尚未收敛，不能建立新报价");
        }
    }

    /** 用重新报价原子替换从未调用钱包的失败预约，保留原 OWNED claim 与 recipient。 */
    public void replaceFailedOwnedQuote(HyperlinkTask task,
            HyperlinkQuoteTokenService.QuoteClaims claims, long now) {
        HyperlinkTaskRecipientClaim claim = claimMapper.selectByTaskId(
                task.getTenantId(), task.getId());
        HyperlinkBillingReservation existing = billingMapper.selectByTaskId(task.getId());
        boolean claimMatches = claim != null && Integer.valueOf(CLAIM_OWNED)
                .equals(claim.getClaimStatus())
                && Objects.equals(claim.getDataPackageId(), claims.dataPackageId())
                && Objects.equals(claim.getDataPackageGeneration(),
                        claims.dataPackageGeneration())
                && Objects.equals(claim.getClaimUpperPhoneId(),
                        claims.claimUpperPhoneId())
                && Objects.equals(claim.getClaimedPhoneCount(),
                        claims.quote().recipientCount())
                && recipientMapper.countByTaskId(task.getId()) == claims.quote().recipientCount()
                && recipientMapper.countCommanded(task.getId()) == 0;
        boolean billingUncalled = isFailedUnstartedReserve(existing);
        if (!claimMatches || !billingUncalled) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "旧领取或计费事实不能安全替换报价");
        }
        HyperlinkBillingReservation replacement = billing(task, claims, null, now);
        if (billingMapper.resetFailedUnstartedForReserve(
                replacement, existing.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "失败报价已被并发恢复");
        }
    }

    private HyperlinkTaskRecipientClaim claim(long taskId,
            HyperlinkQuoteTokenService.QuoteClaims claims, long now) {
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setHyperlinkTaskId(taskId);
        claim.setDataPackageId(claims.dataPackageId());
        claim.setDataPackageGeneration(claims.dataPackageGeneration());
        claim.setClaimUpperPhoneId(claims.claimUpperPhoneId());
        claim.setScanCursorPhoneId(0L);
        claim.setQuotedPhoneCount(claims.quote().recipientCount());
        claim.setClaimStatus(1);
        claim.setStartedAt(now);
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        return claim;
    }

    private HyperlinkBillingReservation billing(HyperlinkTask task,
            HyperlinkQuoteTokenService.QuoteClaims claims,
            HyperlinkBillingReservation existing, long now) {
        HyperlinkBillingReservation billing = new HyperlinkBillingReservation();
        billing.setHyperlinkTaskId(task.getId());
        billing.setBillingProvider(claims.billingProvider());
        billing.setQuoteId(claims.quoteId());
        billing.setQuoteExpiresAt(claims.expiresAt());
        billing.setPriceCode(claims.quote().priceCode());
        billing.setPricingMode("SUPER".equals(claims.quote().pricingMode()) ? 2 : 1);
        billing.setCurrencyCode(claims.quote().currencyCode());
        billing.setUnitPrice(claims.quote().unitPrice());
        try {
            billing.setPricingBreakdown(objectMapper.writeValueAsString(claims.quote().pricingBreakdown()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "报价明细无法序列化");
        }
        billing.setQuotedRecipientCount(claims.quote().recipientCount());
        billing.setQuotedAmount(claims.quote().estimatedAmount());
        billing.setReservationStatus(HyperlinkBillingStatus.PROCESSING.code());
        boolean adjustment = existing != null
                && existing.getReservationStatus() != HyperlinkBillingStatus.RELEASED.code();
        billing.setPendingOperation(adjustment
                ? HyperlinkBillingOperation.ADJUST.code() : HyperlinkBillingOperation.RESERVE.code());
        billing.setOperationIdempotencyKey(operationKey(
                adjustment ? "adjust" : "reserve", task, existing));
        billing.setVersion(1);
        billing.setCreatedAt(now);
        billing.setUpdatedAt(now);
        return billing;
    }

    private boolean canAdjust(HyperlinkBillingReservation existing,
            HyperlinkBillingReservation replacement) {
        return existing.getExternalReservationNo() != null
                && existing.getPendingOperation() == HyperlinkBillingOperation.NONE.code()
                && existing.getSettledSendCount() == 0
                && existing.getSettledAmount().signum() == 0
                && existing.getBillingProvider().equals(replacement.getBillingProvider());
    }

    private boolean isFailedUnstartedReserve(HyperlinkBillingReservation billing) {
        return billing != null
                && Integer.valueOf(HyperlinkBillingStatus.FAILED.code())
                        .equals(billing.getReservationStatus())
                && Integer.valueOf(HyperlinkBillingOperation.RESERVE.code())
                        .equals(billing.getPendingOperation())
                && Integer.toString(ErrorCode.HYPERLINK_QUOTE_STALE.code())
                        .equals(billing.getFailureCode())
                && billing.getExternalReservationNo() == null
                && billing.getReservedAmount() != null
                && billing.getReservedAmount().signum() == 0
                && billing.getSettledAmount() != null
                && billing.getSettledAmount().signum() == 0
                && billing.getSettledSendCount() != null
                && billing.getSettledSendCount() == 0;
    }

    private String operationKey(String action, HyperlinkTask task,
            HyperlinkBillingReservation existing) {
        String reservation = existing == null || existing.getExternalReservationNo() == null
                ? "new" : existing.getExternalReservationNo();
        return HyperlinkBillingOperationKeys.create(
                action, reservation, task.getId(), task.getVersion());
    }
}
