package com.armada.hyperlink.task.model.vo;

/** SQL 查询账号统计和 usage 展示快照的内部行。 */
public class HyperlinkAccountStatRow {

    private Long bucketKey;
    private Long accountId;
    private String senderPhone;
    private String senderCountryIso2;
    private Integer accountTypeCode;
    private Long accountCreatedAt;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long failedNum;
    private Long lastSendAt;

    public Long getBucketKey() { return bucketKey; }
    public void setBucketKey(Long bucketKey) { this.bucketKey = bucketKey; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String senderCountryIso2) {
        this.senderCountryIso2 = senderCountryIso2;
    }
    public Integer getAccountTypeCode() { return accountTypeCode; }
    public void setAccountTypeCode(Integer accountTypeCode) { this.accountTypeCode = accountTypeCode; }
    public Long getAccountCreatedAt() { return accountCreatedAt; }
    public void setAccountCreatedAt(Long accountCreatedAt) { this.accountCreatedAt = accountCreatedAt; }
    public Long getSendTotal() { return sendTotal; }
    public void setSendTotal(Long sendTotal) { this.sendTotal = sendTotal; }
    public Long getSuccessNum() { return successNum; }
    public void setSuccessNum(Long successNum) { this.successNum = successNum; }
    public Long getDeliveredNum() { return deliveredNum; }
    public void setDeliveredNum(Long deliveredNum) { this.deliveredNum = deliveredNum; }
    public Long getFailedNum() { return failedNum; }
    public void setFailedNum(Long failedNum) { this.failedNum = failedNum; }
    public Long getLastSendAt() { return lastSendAt; }
    public void setLastSendAt(Long lastSendAt) { this.lastSendAt = lastSendAt; }
}
