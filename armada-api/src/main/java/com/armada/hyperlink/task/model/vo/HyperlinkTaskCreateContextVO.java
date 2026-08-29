package com.armada.hyperlink.task.model.vo;

import java.math.BigDecimal;
import java.util.List;

/** 新建超链任务打开时所需的真实计价、余额和协议容量上下文。 */
public record HyperlinkTaskCreateContextVO(
        String pricingMode,
        String priceCode,
        String currencyCode,
        BigDecimal referenceUnitPrice,
        BigDecimal accountBalance,
        BigDecimal giftBalance,
        BigDecimal availableBalance,
        int protocolCount,
        int maxConcurrentNum,
        int accountSendConcurrency,
        int defaultSubTaskNum,
        List<Long> defaultAccountGroupIds,
        List<HyperlinkIdOptionVO> groupOptions,
        List<HyperlinkCountryOptionVO> countryOptions,
        List<HyperlinkIdOptionVO> channelOptions,
        List<HyperlinkStringOptionVO> protocolOptions) {
}
