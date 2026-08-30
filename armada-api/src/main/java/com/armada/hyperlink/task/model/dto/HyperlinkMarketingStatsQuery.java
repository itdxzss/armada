package com.armada.hyperlink.task.model.dto;

import com.armada.shared.paging.PageQuery;

/** 市场分析筛选；page 字段仅遵循统一 GET 绑定基类，不参与本查询分页。 */
public class HyperlinkMarketingStatsQuery extends PageQuery {
    private String dateFrom;
    private String dateTo;
    private String granularity;
    private Integer taskType;
    private String senderCountryIso2;
    private String recipientCountryIso2;
    private Integer accountType;
    private String deviceOs;
    private Boolean shortLinkEnabled;

    public String getDateFrom() { return dateFrom; }
    public void setDateFrom(String value) { this.dateFrom = value; }
    public String getDateTo() { return dateTo; }
    public void setDateTo(String value) { this.dateTo = value; }
    public String getGranularity() { return granularity; }
    public void setGranularity(String value) { this.granularity = value; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer value) { this.taskType = value; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String value) { this.senderCountryIso2 = value; }
    public String getRecipientCountryIso2() { return recipientCountryIso2; }
    public void setRecipientCountryIso2(String value) { this.recipientCountryIso2 = value; }
    public Integer getAccountType() { return accountType; }
    public void setAccountType(Integer value) { this.accountType = value; }
    public String getDeviceOs() { return deviceOs; }
    public void setDeviceOs(String value) { this.deviceOs = value; }
    public Boolean getShortLinkEnabled() { return shortLinkEnabled; }
    public void setShortLinkEnabled(Boolean value) { this.shortLinkEnabled = value; }
}
