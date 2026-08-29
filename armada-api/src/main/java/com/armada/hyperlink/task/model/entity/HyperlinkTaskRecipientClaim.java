package com.armada.hyperlink.task.model.entity;

/** 分批领取数据包号码的可恢复游标。 */
public class HyperlinkTaskRecipientClaim {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long dataPackageId;
    private Integer dataPackageGeneration;
    private Long claimUpperPhoneId;
    private Long scanCursorPhoneId;
    private Integer quotedPhoneCount;
    private Integer claimedPhoneCount;
    private Integer claimStatus;
    private String leaseOwner;
    private Long leaseExpiresAt;
    private String failureCode;
    private String failureReason;
    private Integer version;
    private Long startedAt;
    private Long finishedAt;
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
    public Long getClaimUpperPhoneId() { return claimUpperPhoneId; }
    public void setClaimUpperPhoneId(Long value) { this.claimUpperPhoneId = value; }
    public Long getScanCursorPhoneId() { return scanCursorPhoneId; }
    public void setScanCursorPhoneId(Long value) { this.scanCursorPhoneId = value; }
    public Integer getQuotedPhoneCount() { return quotedPhoneCount; }
    public void setQuotedPhoneCount(Integer value) { this.quotedPhoneCount = value; }
    public Integer getClaimedPhoneCount() { return claimedPhoneCount; }
    public void setClaimedPhoneCount(Integer value) { this.claimedPhoneCount = value; }
    public Integer getClaimStatus() { return claimStatus; }
    public void setClaimStatus(Integer value) { this.claimStatus = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { this.leaseOwner = value; }
    public Long getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Long value) { this.leaseExpiresAt = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { this.failureCode = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long value) { this.startedAt = value; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long value) { this.finishedAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
