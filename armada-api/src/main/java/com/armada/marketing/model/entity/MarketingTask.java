package com.armada.marketing.model.entity;

/**
 * 营销任务主表实体,映射 marketing_task。
 */
public class MarketingTask {

    private Long id;
    private Long tenantId;
    private String taskName;
    private Long accountGroupId;
    private String accountGroupName;
    private Long marketingTemplateId;
    private String marketingTemplateName;
    private Integer status;
    private Integer selectedAccountCount;
    private Integer targetGroupCount;
    private Integer targetPairCount;
    private Integer sentMessageCount;
    private Integer failedMessageCount;
    private Integer sendPerRound;
    private Integer sendIntervalSeconds;
    private Boolean onlineCheckEnabled;
    private Boolean abnormalGroupSkipped;
    private Boolean autoRetryEnabled;
    private Integer retryLimit;
    private String remark;
    private Long startedAt;
    private Long lastSentAt;
    private Long finishedAt;
    private Long createdBy;
    private Long createdAt;
    private Long updatedAt;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSelectedAccountCount() {
        return selectedAccountCount;
    }

    public void setSelectedAccountCount(Integer selectedAccountCount) {
        this.selectedAccountCount = selectedAccountCount;
    }

    public Integer getTargetGroupCount() {
        return targetGroupCount;
    }

    public void setTargetGroupCount(Integer targetGroupCount) {
        this.targetGroupCount = targetGroupCount;
    }

    public Integer getTargetPairCount() {
        return targetPairCount;
    }

    public void setTargetPairCount(Integer targetPairCount) {
        this.targetPairCount = targetPairCount;
    }

    public Integer getSentMessageCount() {
        return sentMessageCount;
    }

    public void setSentMessageCount(Integer sentMessageCount) {
        this.sentMessageCount = sentMessageCount;
    }

    public Integer getFailedMessageCount() {
        return failedMessageCount;
    }

    public void setFailedMessageCount(Integer failedMessageCount) {
        this.failedMessageCount = failedMessageCount;
    }

    public Integer getSendPerRound() {
        return sendPerRound;
    }

    public void setSendPerRound(Integer sendPerRound) {
        this.sendPerRound = sendPerRound;
    }

    public Integer getSendIntervalSeconds() {
        return sendIntervalSeconds;
    }

    public void setSendIntervalSeconds(Integer sendIntervalSeconds) {
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    public Boolean getOnlineCheckEnabled() {
        return onlineCheckEnabled;
    }

    public void setOnlineCheckEnabled(Boolean onlineCheckEnabled) {
        this.onlineCheckEnabled = onlineCheckEnabled;
    }

    public Boolean getAbnormalGroupSkipped() {
        return abnormalGroupSkipped;
    }

    public void setAbnormalGroupSkipped(Boolean abnormalGroupSkipped) {
        this.abnormalGroupSkipped = abnormalGroupSkipped;
    }

    public Boolean getAutoRetryEnabled() {
        return autoRetryEnabled;
    }

    public void setAutoRetryEnabled(Boolean autoRetryEnabled) {
        this.autoRetryEnabled = autoRetryEnabled;
    }

    public Integer getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(Integer retryLimit) {
        this.retryLimit = retryLimit;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
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

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
