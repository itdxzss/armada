package com.armada.group.model.entity;

/**
 * 历史群拉人执行快照。
 *
 * <p>每条记录代表一次彼此独立的一次性执行；失败后不在本执行域内自动重试，
 * 调用方通过 {@code idempotencyKey} 防止同一请求被重复创建。</p>
 */
public class HistoricalGroupPullExecution {

    private Long id;
    private Long tenantId;
    private Long createdBy;
    private String idempotencyKey;
    private Long operationAccountId;
    private Long sourceAccountGroupId;
    private String groupJid;
    private String groupSubjectSnapshot;
    private String inviteLink;
    private Long pullerAccountGroupId;
    private Long pullerAccountId;
    private Integer singleAddCount;
    private Long marketingTemplateId;
    private Integer normalCount;
    private Integer marketingCount;
    private Integer invalidCount;
    private Integer duplicateCount;
    private Integer pullSuccessCount;
    private Integer pullFailureCount;
    private Integer sendSuccessCount;
    private Integer sendFailureCount;
    private Integer pullStatus;
    private Integer marketingStatus;
    private String failureStage;
    private String errorCode;
    private String errorMessage;
    private Long startedAt;
    private Long finishedAt;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getOperationAccountId() { return operationAccountId; }
    public void setOperationAccountId(Long operationAccountId) { this.operationAccountId = operationAccountId; }
    public Long getSourceAccountGroupId() { return sourceAccountGroupId; }
    public void setSourceAccountGroupId(Long sourceAccountGroupId) {
        this.sourceAccountGroupId = sourceAccountGroupId;
    }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public String getGroupSubjectSnapshot() { return groupSubjectSnapshot; }
    public void setGroupSubjectSnapshot(String groupSubjectSnapshot) { this.groupSubjectSnapshot = groupSubjectSnapshot; }
    public String getInviteLink() { return inviteLink; }
    public void setInviteLink(String inviteLink) { this.inviteLink = inviteLink; }
    public Long getPullerAccountGroupId() { return pullerAccountGroupId; }
    public void setPullerAccountGroupId(Long pullerAccountGroupId) { this.pullerAccountGroupId = pullerAccountGroupId; }
    public Long getPullerAccountId() { return pullerAccountId; }
    public void setPullerAccountId(Long pullerAccountId) { this.pullerAccountId = pullerAccountId; }
    public Integer getSingleAddCount() { return singleAddCount; }
    public void setSingleAddCount(Integer singleAddCount) { this.singleAddCount = singleAddCount; }
    public Long getMarketingTemplateId() { return marketingTemplateId; }
    public void setMarketingTemplateId(Long marketingTemplateId) { this.marketingTemplateId = marketingTemplateId; }
    public Integer getNormalCount() { return normalCount; }
    public void setNormalCount(Integer normalCount) { this.normalCount = normalCount; }
    public Integer getMarketingCount() { return marketingCount; }
    public void setMarketingCount(Integer marketingCount) { this.marketingCount = marketingCount; }
    public Integer getInvalidCount() { return invalidCount; }
    public void setInvalidCount(Integer invalidCount) { this.invalidCount = invalidCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Integer getPullSuccessCount() { return pullSuccessCount; }
    public void setPullSuccessCount(Integer pullSuccessCount) { this.pullSuccessCount = pullSuccessCount; }
    public Integer getPullFailureCount() { return pullFailureCount; }
    public void setPullFailureCount(Integer pullFailureCount) { this.pullFailureCount = pullFailureCount; }
    public Integer getSendSuccessCount() { return sendSuccessCount; }
    public void setSendSuccessCount(Integer sendSuccessCount) { this.sendSuccessCount = sendSuccessCount; }
    public Integer getSendFailureCount() { return sendFailureCount; }
    public void setSendFailureCount(Integer sendFailureCount) { this.sendFailureCount = sendFailureCount; }
    public Integer getPullStatus() { return pullStatus; }
    public void setPullStatus(Integer pullStatus) { this.pullStatus = pullStatus; }
    public Integer getMarketingStatus() { return marketingStatus; }
    public void setMarketingStatus(Integer marketingStatus) { this.marketingStatus = marketingStatus; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
