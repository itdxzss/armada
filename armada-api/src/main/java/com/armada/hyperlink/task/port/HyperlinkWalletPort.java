package com.armada.hyperlink.task.port;

import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import java.math.BigDecimal;
import java.util.List;

/**
 * 钱包域可插拔端口。实现方必须支持 operationKey 幂等；Armada 不在本地伪造钱包总账。
 */
public interface HyperlinkWalletPort {

    PricingSnapshot quote(long tenantId, int maxExecutingAccounts,
            List<HyperlinkRecipientCountryCount> recipientCounts);

    ReserveResult reserve(long tenantId, long taskId, String operationKey,
            String currencyCode, BigDecimal amount);

    AdjustmentResult adjust(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReservedAmount);

    SettlementResult settle(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetSettledAmount,
            long targetSettledSendCount);

    ReleaseResult release(long tenantId, long taskId, String operationKey,
            String externalReservationNo, String currencyCode, BigDecimal targetReleasedAmount);

    record CountryPrice(String countryIso2, int recipientCount,
                        BigDecimal unitPrice, BigDecimal amount) { }

    record PricingSnapshot(String provider, String pricingMode, String priceCode,
                           String currencyCode, BigDecimal unitPrice,
                           List<CountryPrice> breakdown, BigDecimal estimatedAmount,
                           BigDecimal accountBalance, BigDecimal giftBalance) { }

    record ReserveResult(String externalReservationNo, BigDecimal reservedAmount) { }

    record AdjustmentResult(BigDecimal reservedAmount) { }

    record SettlementResult(BigDecimal settledAmount, long settledSendCount) { }

    record ReleaseResult(BigDecimal releasedAmount) { }
}
