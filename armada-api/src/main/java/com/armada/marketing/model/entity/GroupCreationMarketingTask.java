package com.armada.marketing.model.entity;

public class GroupCreationMarketingTask {

    private Long id;
    private Long tenantId;
    private Long ownerUserId;
    private String taskName;
    private Long accountGroupId;
    private String accountGroupName;
    private Long marketingTemplateId;
    private String marketingTemplateName;
    private Long marketingTaskId;
    private Integer status;
    private Integer matchedItemCount;
    private Integer unmatchedFileCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer abandonedCount;
    private Integer sendIntervalSeconds;
    private String groupNamePrefix;
    private String remark;
    private Long createdBy;
    private Long createdAt;
    private Long updatedAt;
    private Long finishedAt;
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getAccountGroupName() {
        return accountGroupName;
    }

    public void setAccountGroupName(String accountGroupName) {
        this.accountGroupName = accountGroupName;
    }

    public Long getMarketingTemplateId() {
        return marketingTemplateId;
    }

    public void setMarketingTemplateId(Long marketingTemplateId) {
        this.marketingTemplateId = marketingTemplateId;
    }

    public String getMarketingTemplateName() {
        return marketingTemplateName;
    }

    public void setMarketingTemplateName(String marketingTemplateName) {
        this.marketingTemplateName = marketingTemplateName;
    }

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMatchedItemCount() {
        return matchedItemCount;
    }

    public void setMatchedItemCount(Integer matchedItemCount) {
        this.matchedItemCount = matchedItemCount;
    }

    public Integer getUnmatchedFileCount() {
        return unmatchedFileCount;
    }

    public void setUnmatchedFileCount(Integer unmatchedFileCount) {
        this.unmatchedFileCount = unmatchedFileCount;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Integer successCount) {
        this.successCount = successCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public Integer getAbandonedCount() {
        return abandonedCount;
    }

    public void setAbandonedCount(Integer abandonedCount) {
        this.abandonedCount = abandonedCount;
    }

    public Integer getSendIntervalSeconds() {
        return sendIntervalSeconds;
    }

    public void setSendIntervalSeconds(Integer sendIntervalSeconds) {
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    public String getGroupNamePrefix() {
        return groupNamePrefix;
    }

    public void setGroupNamePrefix(String groupNamePrefix) {
        this.groupNamePrefix = groupNamePrefix;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
