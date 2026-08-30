package com.armada.hyperlink.task.model.query;

/** 已校验并归一化的市场分析 SQL 条件。 */
public record HyperlinkMarketingStatCriteria(
        long tenantId,
        String granularity,
        long startAt,
        long endExclusiveAt,
        int statDateFrom,
        int statDateTo,
        Integer taskType,
        String senderCountryIso2,
        String recipientCountryIso2,
        Integer accountType,
        Integer senderDeviceOs,
        Boolean shortLinkEnabled) {
}
