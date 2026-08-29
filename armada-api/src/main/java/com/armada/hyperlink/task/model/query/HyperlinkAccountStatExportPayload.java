package com.armada.hyperlink.task.model.query;

import java.math.BigDecimal;

/** ACCOUNT_STATS 作业持久化的规范化筛选快照。 */
public record HyperlinkAccountStatExportPayload(
        Long startAt,
        Long endAt,
        String senderCountryIso2,
        BigDecimal successRateMin,
        BigDecimal successRateMax,
        String sortField,
        String sortOrder) {
}
