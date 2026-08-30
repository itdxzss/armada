package com.armada.hyperlink.task.model.entity;

/** 任务级账号容量与调度同步状态。 */
public class HyperlinkTaskAccountUsage {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long accountId;
    private String accountPhoneSnapshot;
    private String senderCountryIso2Snapshot;
    private Integer accountTypeSnapshot;
    private Integer senderDeviceOsSnapshot;
    private Long accountCreatedAtSnapshot;
    private String protocolIdSnapshot;
    private String protocolAccountIdSnapshot;
    private Integer protocolBackend;
    private Integer successLimit;
    private Long successfulSendCount;
    private Integer reservedSuccessSlotCount;
    private Integer inFlightCount;
    private Integer usageStatus;
    private String invalidCode;
    private String invalidReason;
    private Long invalidAt;
    private Long lastSelectedRoundNo;
    private Long nextSendAt;
    private Long firstUsedAt;
    private Long lastUsedAt;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
    public String getAccountPhoneSnapshot() { return accountPhoneSnapshot; }
    public void setAccountPhoneSnapshot(String value) { this.accountPhoneSnapshot = value; }
    public String getSenderCountryIso2Snapshot() { return senderCountryIso2Snapshot; }
    public void setSenderCountryIso2Snapshot(String value) { this.senderCountryIso2Snapshot = value; }
    public Integer getAccountTypeSnapshot() { return accountTypeSnapshot; }
    public void setAccountTypeSnapshot(Integer value) { this.accountTypeSnapshot = value; }
    public Integer getSenderDeviceOsSnapshot() { return senderDeviceOsSnapshot; }
    public void setSenderDeviceOsSnapshot(Integer value) { this.senderDeviceOsSnapshot = value; }
    public Long getAccountCreatedAtSnapshot() { return accountCreatedAtSnapshot; }
    public void setAccountCreatedAtSnapshot(Long value) { this.accountCreatedAtSnapshot = value; }
    public String getProtocolIdSnapshot() { return protocolIdSnapshot; }
    public void setProtocolIdSnapshot(String value) { this.protocolIdSnapshot = value; }
    public String getProtocolAccountIdSnapshot() { return protocolAccountIdSnapshot; }
    public void setProtocolAccountIdSnapshot(String value) { this.protocolAccountIdSnapshot = value; }
    public Integer getProtocolBackend() { return protocolBackend; }
    public void setProtocolBackend(Integer value) { this.protocolBackend = value; }
    public Integer getSuccessLimit() { return successLimit; }
    public void setSuccessLimit(Integer value) { this.successLimit = value; }
    public Long getSuccessfulSendCount() { return successfulSendCount; }
    public void setSuccessfulSendCount(Long value) { this.successfulSendCount = value; }
    public Integer getReservedSuccessSlotCount() { return reservedSuccessSlotCount; }
    public void setReservedSuccessSlotCount(Integer value) { this.reservedSuccessSlotCount = value; }
    public Integer getInFlightCount() { return inFlightCount; }
    public void setInFlightCount(Integer value) { this.inFlightCount = value; }
    public Integer getUsageStatus() { return usageStatus; }
    public void setUsageStatus(Integer value) { this.usageStatus = value; }
    public String getInvalidCode() { return invalidCode; }
    public void setInvalidCode(String value) { this.invalidCode = value; }
    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String value) { this.invalidReason = value; }
    public Long getInvalidAt() { return invalidAt; }
    public void setInvalidAt(Long value) { this.invalidAt = value; }
    public Long getLastSelectedRoundNo() { return lastSelectedRoundNo; }
    public void setLastSelectedRoundNo(Long value) { this.lastSelectedRoundNo = value; }
    public Long getNextSendAt() { return nextSendAt; }
    public void setNextSendAt(Long value) { this.nextSendAt = value; }
    public Long getFirstUsedAt() { return firstUsedAt; }
    public void setFirstUsedAt(Long value) { this.firstUsedAt = value; }
    public Long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Long value) { this.lastUsedAt = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
