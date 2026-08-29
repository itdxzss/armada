package com.armada.hyperlink.task.model.dto;

import com.armada.shared.paging.PageQuery;
import java.math.BigDecimal;

/** GET 账号统计分页查询；默认每页 20 条。 */
public class HyperlinkAccountStatQuery extends PageQuery {

    private Long startAt;
    private Long endAt;
    private String senderCountryIso2;
    private BigDecimal successRateMin;
    private BigDecimal successRateMax;
    private String sortField = "successNum";
    private String sortOrder = "desc";

    public HyperlinkAccountStatQuery() {
        setPageSize(20);
    }

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

    /** 转为不含分页的导出筛选。 */
    public HyperlinkAccountStatFilterDTO toFilter() {
        HyperlinkAccountStatFilterDTO filter = new HyperlinkAccountStatFilterDTO();
        filter.setStartAt(startAt);
        filter.setEndAt(endAt);
        filter.setSenderCountryIso2(senderCountryIso2);
        filter.setSuccessRateMin(successRateMin);
        filter.setSuccessRateMax(successRateMax);
        filter.setSortField(sortField);
        filter.setSortOrder(sortOrder);
        return filter;
    }
}
