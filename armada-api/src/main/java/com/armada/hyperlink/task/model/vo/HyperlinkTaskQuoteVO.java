package com.armada.hyperlink.task.model.vo;

import java.math.BigDecimal;
import java.util.List;

/** 由服务端签名并冻结快照的任务报价。 */
public record HyperlinkTaskQuoteVO(
        String quoteToken,
        long expiresAt,
        long dataPackageId,
        int dataPackageGeneration,
        String dataPackageName,
        int recipientCount,
        String pricingMode,
        String priceCode,
        String currencyCode,
        BigDecimal unitPrice,
        List<HyperlinkTaskQuoteBreakdownVO> pricingBreakdown,
        BigDecimal estimatedAmount,
        BigDecimal accountBalance,
        BigDecimal giftBalance,
        BigDecimal availableBalance) {
}
