package com.armada.hyperlink.task.model.entity;

/** 一行代表任务内一个收件号码的一次逻辑发送。 */
public class HyperlinkTaskRecipient {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long dataPackageId;
    private Integer dataPackageGeneration;
    private Long sourceImportId;
    private String recipientPhoneSnapshot;
    private String recipientCountryIso2Snapshot;
    private Long hyperlinkTaskRoundId;
    private Long roundNo;
    private Long accountId;
    private String senderPhoneSnapshot;
    private String senderCountryIso2Snapshot;
    private Integer senderAccountTypeSnapshot;
    private String protocolId;
    private Integer protocolBackend;
    private String commandId;
    private String protocolMessageId;
    private String shortCode;
    private Integer sendStatus;
    private Long nextDispatchAt;
    private Integer metricsProjectedStatus;
    private String failCode;
    private String failReason;
    private Long submittedAt;
    private Long sentAt;
    private Long deliveredAt;
    private Long readAt;
    private Long failedAt;
    private Integer clickCount;
    private Long firstVisitAt;
    private Long lastVisitAt;
    private byte[] firstVisitIpAddress;
    private String firstVisitUserAgent;
    private String firstVisitBrowser;
    private String firstVisitOs;
    private String firstVisitDevice;
    private String firstVisitLanguage;
    private String firstVisitCountryIso2;
    private Long attributionPurgedAt;
    private Long metricsProjectedAt;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long value) { this.dataPackageId = value; }
    public Integer getDataPackageGeneration() { return dataPackageGeneration; }
    public void setDataPackageGeneration(Integer value) { this.dataPackageGeneration = value; }
    public Long getSourceImportId() { return sourceImportId; }
    public void setSourceImportId(Long value) { this.sourceImportId = value; }
    public String getRecipientPhoneSnapshot() { return recipientPhoneSnapshot; }
    public void setRecipientPhoneSnapshot(String value) { this.recipientPhoneSnapshot = value; }
    public String getRecipientCountryIso2Snapshot() { return recipientCountryIso2Snapshot; }
    public void setRecipientCountryIso2Snapshot(String value) { this.recipientCountryIso2Snapshot = value; }
    public Long getHyperlinkTaskRoundId() { return hyperlinkTaskRoundId; }
    public void setHyperlinkTaskRoundId(Long value) { this.hyperlinkTaskRoundId = value; }
    public Long getRoundNo() { return roundNo; }
    public void setRoundNo(Long value) { this.roundNo = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
    public String getSenderPhoneSnapshot() { return senderPhoneSnapshot; }
    public void setSenderPhoneSnapshot(String value) { this.senderPhoneSnapshot = value; }
    public String getSenderCountryIso2Snapshot() { return senderCountryIso2Snapshot; }
    public void setSenderCountryIso2Snapshot(String value) { this.senderCountryIso2Snapshot = value; }
    public Integer getSenderAccountTypeSnapshot() { return senderAccountTypeSnapshot; }
    public void setSenderAccountTypeSnapshot(Integer value) { this.senderAccountTypeSnapshot = value; }
    public String getProtocolId() { return protocolId; }
    public void setProtocolId(String value) { this.protocolId = value; }
    public Integer getProtocolBackend() { return protocolBackend; }
    public void setProtocolBackend(Integer value) { this.protocolBackend = value; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String value) { this.commandId = value; }
    public String getProtocolMessageId() { return protocolMessageId; }
    public void setProtocolMessageId(String value) { this.protocolMessageId = value; }
    public String getShortCode() { return shortCode; }
    public void setShortCode(String value) { this.shortCode = value; }
    public Integer getSendStatus() { return sendStatus; }
    public void setSendStatus(Integer value) { this.sendStatus = value; }
    public Long getNextDispatchAt() { return nextDispatchAt; }
    public void setNextDispatchAt(Long value) { this.nextDispatchAt = value; }
    public Integer getMetricsProjectedStatus() { return metricsProjectedStatus; }
    public void setMetricsProjectedStatus(Integer value) { this.metricsProjectedStatus = value; }
    public String getFailCode() { return failCode; }
    public void setFailCode(String value) { this.failCode = value; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String value) { this.failReason = value; }
    public Long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Long value) { this.submittedAt = value; }
    public Long getSentAt() { return sentAt; }
    public void setSentAt(Long value) { this.sentAt = value; }
    public Long getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Long value) { this.deliveredAt = value; }
    public Long getReadAt() { return readAt; }
    public void setReadAt(Long value) { this.readAt = value; }
    public Long getFailedAt() { return failedAt; }
    public void setFailedAt(Long value) { this.failedAt = value; }
    public Integer getClickCount() { return clickCount; }
    public void setClickCount(Integer value) { this.clickCount = value; }
    public Long getFirstVisitAt() { return firstVisitAt; }
    public void setFirstVisitAt(Long value) { this.firstVisitAt = value; }
    public Long getLastVisitAt() { return lastVisitAt; }
    public void setLastVisitAt(Long value) { this.lastVisitAt = value; }
    public byte[] getFirstVisitIpAddress() { return firstVisitIpAddress; }
    public void setFirstVisitIpAddress(byte[] value) { this.firstVisitIpAddress = value; }
    public String getFirstVisitUserAgent() { return firstVisitUserAgent; }
    public void setFirstVisitUserAgent(String value) { this.firstVisitUserAgent = value; }
    public String getFirstVisitBrowser() { return firstVisitBrowser; }
    public void setFirstVisitBrowser(String value) { this.firstVisitBrowser = value; }
    public String getFirstVisitOs() { return firstVisitOs; }
    public void setFirstVisitOs(String value) { this.firstVisitOs = value; }
    public String getFirstVisitDevice() { return firstVisitDevice; }
    public void setFirstVisitDevice(String value) { this.firstVisitDevice = value; }
    public String getFirstVisitLanguage() { return firstVisitLanguage; }
    public void setFirstVisitLanguage(String value) { this.firstVisitLanguage = value; }
    public String getFirstVisitCountryIso2() { return firstVisitCountryIso2; }
    public void setFirstVisitCountryIso2(String value) { this.firstVisitCountryIso2 = value; }
    public Long getAttributionPurgedAt() { return attributionPurgedAt; }
    public void setAttributionPurgedAt(Long value) { this.attributionPurgedAt = value; }
    public Long getMetricsProjectedAt() { return metricsProjectedAt; }
    public void setMetricsProjectedAt(Long value) { this.metricsProjectedAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
