package com.armada.hyperlink.task.model.dto;

import java.math.BigDecimal;

/** 发信账号统计筛选与排序字段；分页查询和导出共享同一口径。 */
public class HyperlinkAccountStatFilterDTO {

    private Long startAt;
    private Long endAt;
    private String senderCountryIso2;
    private BigDecimal successRateMin;
    private BigDecimal successRateMax;
    private String sortField = "successNum";
    private String sortOrder = "desc";

    public Long getStartAt() { return startAt; }
    public void setStartAt(Long startAt) { this.startAt = startAt; }
    public Long getEndAt() { return endAt; }
    public void setEndAt(Long endAt) { this.endAt = endAt; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String senderCountryIso2) {
        this.senderCountryIso2 = senderCountryIso2;
    }
    public BigDecimal getSuccessRateMin() { return successRateMin; }
    public void setSuccessRateMin(BigDecimal successRateMin) {
        this.successRateMin = successRateMin;
    }
    public BigDecimal getSuccessRateMax() { return successRateMax; }
    public void setSuccessRateMax(BigDecimal successRateMax) {
        this.successRateMax = successRateMax;
    }
    public String getSortField() { return sortField; }
    public void setSortField(String sortField) { this.sortField = sortField; }
    public String getSortOrder() { return sortOrder; }
    public void setSortOrder(String sortOrder) { this.sortOrder = sortOrder; }
}
