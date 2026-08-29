package com.armada.hyperlink.task.model.entity;

/** 超链任务冻结配置，映射 {@code hyperlink_task}。 */
public class HyperlinkTask {
    private Long id;
    private Long tenantId;
    private String taskName;
    private Integer taskType;
    private Integer startMode;
    private Integer taskDelayMinutes;
    private Long taskPlannedEndAt;
    private Integer taskIntervalMinutes;
    private Long dataPackageId;
    private Integer dataPackageGeneration;
    private String dataPackageNameSnapshot;
    private String targetCountryIso2sSnapshot;
    private Long sourceTemplateId;
    private Integer sourceTemplateVersion;
    private Long hyperlinkStrategyId;
    private String accountFilter;
    private Integer maxUseAccount;
    private Integer concurrentNum;
    private Integer accountMaxSendNum;
    private Integer accountSendConcurrency;
    private Integer msgIntervalMinMs;
    private Integer msgIntervalMaxMs;
    private Boolean shortLinkEnabled;
    private Integer version;
    private Long createdBy;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer taskType) { this.taskType = taskType; }
    public Integer getStartMode() { return startMode; }
    public void setStartMode(Integer startMode) { this.startMode = startMode; }
    public Integer getTaskDelayMinutes() { return taskDelayMinutes; }
    public void setTaskDelayMinutes(Integer value) { this.taskDelayMinutes = value; }
    public Long getTaskPlannedEndAt() { return taskPlannedEndAt; }
    public void setTaskPlannedEndAt(Long value) { this.taskPlannedEndAt = value; }
    public Integer getTaskIntervalMinutes() { return taskIntervalMinutes; }
    public void setTaskIntervalMinutes(Integer value) { this.taskIntervalMinutes = value; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long value) { this.dataPackageId = value; }
    public Integer getDataPackageGeneration() { return dataPackageGeneration; }
    public void setDataPackageGeneration(Integer value) { this.dataPackageGeneration = value; }
    public String getDataPackageNameSnapshot() { return dataPackageNameSnapshot; }
    public void setDataPackageNameSnapshot(String value) { this.dataPackageNameSnapshot = value; }
    public String getTargetCountryIso2sSnapshot() { return targetCountryIso2sSnapshot; }
    public void setTargetCountryIso2sSnapshot(String value) { this.targetCountryIso2sSnapshot = value; }
    public Long getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(Long value) { this.sourceTemplateId = value; }
    public Integer getSourceTemplateVersion() { return sourceTemplateVersion; }
    public void setSourceTemplateVersion(Integer value) { this.sourceTemplateVersion = value; }
    public Long getHyperlinkStrategyId() { return hyperlinkStrategyId; }
    public void setHyperlinkStrategyId(Long value) { this.hyperlinkStrategyId = value; }
    public String getAccountFilter() { return accountFilter; }
    public void setAccountFilter(String value) { this.accountFilter = value; }
    public Integer getMaxUseAccount() { return maxUseAccount; }
    public void setMaxUseAccount(Integer value) { this.maxUseAccount = value; }
    public Integer getConcurrentNum() { return concurrentNum; }
    public void setConcurrentNum(Integer value) { this.concurrentNum = value; }
    public Integer getAccountMaxSendNum() { return accountMaxSendNum; }
    public void setAccountMaxSendNum(Integer value) { this.accountMaxSendNum = value; }
    public Integer getAccountSendConcurrency() { return accountSendConcurrency; }
    public void setAccountSendConcurrency(Integer value) { this.accountSendConcurrency = value; }
    public Integer getMsgIntervalMinMs() { return msgIntervalMinMs; }
    public void setMsgIntervalMinMs(Integer value) { this.msgIntervalMinMs = value; }
    public Integer getMsgIntervalMaxMs() { return msgIntervalMaxMs; }
    public void setMsgIntervalMaxMs(Integer value) { this.msgIntervalMaxMs = value; }
    public Boolean getShortLinkEnabled() { return shortLinkEnabled; }
    public void setShortLinkEnabled(Boolean value) { this.shortLinkEnabled = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { this.createdBy = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
