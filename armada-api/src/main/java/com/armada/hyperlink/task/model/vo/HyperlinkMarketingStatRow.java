package com.armada.hyperlink.task.model.vo;

/** 日/小时聚合 Mapper 共用的内部行。 */
public class HyperlinkMarketingStatRow {
    private Long statTime;
    private String senderCountryIso2;
    private String recipientCountryIso2;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long usedAccountCount;
    private Long bannedAccountCount;
    private Long clickUvNum;
    private Long updatedAt;

    public Long getStatTime() { return statTime; }
    public void setStatTime(Long value) { this.statTime = value; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String value) { this.senderCountryIso2 = value; }
    public String getRecipientCountryIso2() { return recipientCountryIso2; }
    public void setRecipientCountryIso2(String value) { this.recipientCountryIso2 = value; }
    public Long getSendTotal() { return sendTotal; }
    public void setSendTotal(Long value) { this.sendTotal = value; }
    public Long getSuccessNum() { return successNum; }
    public void setSuccessNum(Long value) { this.successNum = value; }
    public Long getDeliveredNum() { return deliveredNum; }
    public void setDeliveredNum(Long value) { this.deliveredNum = value; }
    public Long getUsedAccountCount() { return usedAccountCount; }
    public void setUsedAccountCount(Long value) { this.usedAccountCount = value; }
    public Long getBannedAccountCount() { return bannedAccountCount; }
    public void setBannedAccountCount(Long value) { this.bannedAccountCount = value; }
    public Long getClickUvNum() { return clickUvNum; }
    public void setClickUvNum(Long value) { this.clickUvNum = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
