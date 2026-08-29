package com.armada.hyperlink.task.port;

import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;

/** 未接钱包适配器时 fail-closed，绝不以本地假余额启用任务。 */
public class UnavailableHyperlinkWalletPort implements HyperlinkWalletPort {
    @Override
    public PricingSnapshot quote(long tenantId, int maxExecutingAccounts,
            List<HyperlinkRecipientCountryCount> recipientCounts) {
        throw unavailable();
    }

    @Override
    public ReserveResult reserve(long tenantId, long taskId, String operationKey,
            String currencyCode, BigDecimal amount) {
        throw unavailable();
    }

    @Override
    public AdjustmentResult adjust(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReservedAmount) {
        throw unavailable();
    }

    @Override
    public SettlementResult settle(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetSettledAmount,
            long targetSettledSendCount) {
        throw unavailable();
    }

    @Override
    public ReleaseResult release(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReleasedAmount) {
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                "钱包适配器未配置，任务启用门禁保持关闭");
    }
}
