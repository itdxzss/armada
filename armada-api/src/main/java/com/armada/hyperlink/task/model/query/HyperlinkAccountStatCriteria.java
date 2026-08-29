package com.armada.hyperlink.task.model.query;

import java.math.BigDecimal;

/** 已校验、可安全传给 MyBatis 的账号统计查询条件。 */
public record HyperlinkAccountStatCriteria(
        long taskId,
        Long startAt,
        Long endAt,
        String senderCountryIso2,
        BigDecimal successRateMin,
        BigDecimal successRateMax,
        String sortField,
        String sortOrder,
        int offset,
        int pageSize,
        Long snapshotAt) {

    public boolean timeScoped() {
        return startAt != null;
    }

    public boolean unknownCountry() {
        return "UNKNOWN".equals(senderCountryIso2);
    }

    public HyperlinkAccountStatCriteria withPage(int newOffset, int newPageSize) {
        return new HyperlinkAccountStatCriteria(taskId, startAt, endAt, senderCountryIso2,
                successRateMin, successRateMax, sortField, sortOrder,
                newOffset, newPageSize, snapshotAt);
    }
}
