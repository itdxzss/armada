package com.armada.hyperlink.task.model.vo;

/** Mapper 小字段投影；服务层负责把数据库状态码映射为公共枚举。 */
public class HyperlinkRecipientRow {
    private Long id;
    private String recipientPhone;
    private String recipientCountryIso2;
    private Long accountId;
    private String senderPhone;
    private String senderCountryIso2;
    private Integer statusCode;
    private String failCode;
    private String failReason;
    private Long statusAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String value) { this.recipientPhone = value; }
    public String getRecipientCountryIso2() { return recipientCountryIso2; }
    public void setRecipientCountryIso2(String value) { this.recipientCountryIso2 = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String value) { this.senderCountryIso2 = value; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public String getFailCode() { return failCode; }
    public void setFailCode(String failCode) { this.failCode = failCode; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Long getStatusAt() { return statusAt; }
    public void setStatusAt(Long statusAt) { this.statusAt = statusAt; }
}
