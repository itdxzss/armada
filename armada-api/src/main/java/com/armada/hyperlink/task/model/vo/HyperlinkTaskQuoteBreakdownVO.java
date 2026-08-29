package com.armada.hyperlink.task.model.vo;

import java.math.BigDecimal;

/** 按收件国家冻结的报价明细。 */
public record HyperlinkTaskQuoteBreakdownVO(
        String recipientCountryIso2,
        int recipientCount,
        BigDecimal unitPrice,
        BigDecimal amount) {
}
