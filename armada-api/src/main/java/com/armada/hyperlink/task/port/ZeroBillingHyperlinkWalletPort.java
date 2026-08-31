package com.armada.hyperlink.task.port;

import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;

/**
 * 测试环境零计费提供方。
 *
 * <p>该实现只接受零金额操作，以纯函数方式返回稳定幂等结果，不伪造余额或外部资金流水。</p>
 */
public class ZeroBillingHyperlinkWalletPort implements HyperlinkWalletPort {
    private static final String PROVIDER = "ZERO_TEST";
    private static final String PRICING_MODE = "NORMAL";
    private static final String PRICE_CODE = "ZERO_TEST_V1";
    private static final String CURRENCY_CODE = "USD";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** {@inheritDoc} */
    @Override
    public PricingSnapshot quote(long tenantId, int maxExecutingAccounts,
            List<HyperlinkRecipientCountryCount> recipientCounts) {
        if (recipientCounts == null) {
            throw unavailable("零计费报价缺少国家人数明细");
        }
        List<CountryPrice> breakdown = recipientCounts.stream()
                .map(this::countryPrice)
                .toList();
        return new PricingSnapshot(PROVIDER, PRICING_MODE, PRICE_CODE, CURRENCY_CODE,
                ZERO, breakdown, ZERO, ZERO, ZERO);
    }

    /** {@inheritDoc} */
    @Override
    public ReserveResult reserve(long tenantId, long taskId, String operationKey,
            String currencyCode, BigDecimal amount) {
        requireOperation(operationKey, currencyCode, amount);
        return new ReserveResult(operationKey, ZERO);
    }

    /** {@inheritDoc} */
    @Override
    public AdjustmentResult adjust(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReservedAmount) {
        requireReservation(externalReservationNo);
        requireOperation(operationKey, currencyCode, targetReservedAmount);
        return new AdjustmentResult(ZERO);
    }

    /** {@inheritDoc} */
    @Override
    public SettlementResult settle(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetSettledAmount,
            long targetSettledSendCount) {
        requireReservation(externalReservationNo);
        requireOperation(operationKey, currencyCode, targetSettledAmount);
        if (targetSettledSendCount < 0) {
            throw unavailable("零计费结算发送数不能为负数");
        }
        return new SettlementResult(ZERO, targetSettledSendCount);
    }

    /** {@inheritDoc} */
    @Override
    public ReleaseResult release(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReleasedAmount) {
        requireReservation(externalReservationNo);
        requireOperation(operationKey, currencyCode, targetReleasedAmount);
        return new ReleaseResult(ZERO);
    }

    private CountryPrice countryPrice(HyperlinkRecipientCountryCount row) {
        if (row == null || row.countryIso2() == null || row.countryIso2().isBlank()
                || row.recipientCount() < 0) {
            throw unavailable("零计费报价国家人数明细非法");
        }
        return new CountryPrice(row.countryIso2(), row.recipientCount(), ZERO, ZERO);
    }

    private void requireOperation(String operationKey, String currencyCode, BigDecimal amount) {
        if (operationKey == null || operationKey.isBlank()
                || currencyCode == null || currencyCode.isBlank()) {
            throw unavailable("零计费操作幂等键或币种缺失");
        }
        if (amount == null || amount.signum() != 0) {
            throw unavailable("零计费模式拒绝非零金额操作");
        }
    }

    private void requireReservation(String externalReservationNo) {
        if (externalReservationNo == null || externalReservationNo.isBlank()) {
            throw unavailable("零计费预约单号缺失");
        }
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE, message);
    }
}
