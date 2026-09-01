package com.armada.hyperlink.task.model.vo;

/** MyBatis 对 task/content/runtime 三张一对一表的列表投影。 */
public class HyperlinkTaskListRow {
    private Long id;
    private String taskName;
    private Integer taskType;
    private Integer messageType;
    private Boolean enabled;
    private Integer runStatus;
    private Integer provisionStatus;
    private Boolean shortLinkEnabled;
    private Integer version;
    private String promotionLink;
    private Long dataPackageId;
    private String dataPackageName;
    private String accountFilterJson;
    private String targetCountryIso2sJson;
    private Long plannedEndAt;
    private Integer cycleIntervalMinutes;
    private Long createdAt;
    private Integer recipientTotal;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long readNum;
    private Long failedNum;
    private Long unregisteredNum;
    private Integer usedAccountCount;
    private Integer invalidAccountCount;
    private Integer clickUvNum;
    private Long clickTotal;
    private Integer actualConcurrency;
    private Long executionDurationSec;
    private Long activeSinceAt;
    private Long metricsUpdatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String value) { this.taskName = value; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer value) { this.taskType = value; }
    public Integer getMessageType() { return messageType; }
    public void setMessageType(Integer value) { this.messageType = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { this.enabled = value; }
    public Integer getRunStatus() { return runStatus; }
    public void setRunStatus(Integer value) { this.runStatus = value; }
    public Integer getProvisionStatus() { return provisionStatus; }
    public void setProvisionStatus(Integer value) { this.provisionStatus = value; }
    public Boolean getShortLinkEnabled() { return shortLinkEnabled; }
    public void setShortLinkEnabled(Boolean value) { this.shortLinkEnabled = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public String getPromotionLink() { return promotionLink; }
    public void setPromotionLink(String value) { this.promotionLink = value; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long value) { this.dataPackageId = value; }
    public String getDataPackageName() { return dataPackageName; }
    public void setDataPackageName(String value) { this.dataPackageName = value; }
    public String getAccountFilterJson() { return accountFilterJson; }
    public void setAccountFilterJson(String value) { this.accountFilterJson = value; }
    public String getTargetCountryIso2sJson() { return targetCountryIso2sJson; }
    public void setTargetCountryIso2sJson(String value) { this.targetCountryIso2sJson = value; }
    public Long getPlannedEndAt() { return plannedEndAt; }
    public void setPlannedEndAt(Long value) { this.plannedEndAt = value; }
    public Integer getCycleIntervalMinutes() { return cycleIntervalMinutes; }
    public void setCycleIntervalMinutes(Integer value) { this.cycleIntervalMinutes = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Integer getRecipientTotal() { return recipientTotal; }
    public void setRecipientTotal(Integer value) { this.recipientTotal = value; }
    public Long getSendTotal() { return sendTotal; }
    public void setSendTotal(Long value) { this.sendTotal = value; }
    public Long getSuccessNum() { return successNum; }
    public void setSuccessNum(Long value) { this.successNum = value; }
    public Long getDeliveredNum() { return deliveredNum; }
    public void setDeliveredNum(Long value) { this.deliveredNum = value; }
    public Long getReadNum() { return readNum; }
    public void setReadNum(Long value) { this.readNum = value; }
    public Long getFailedNum() { return failedNum; }
    public void setFailedNum(Long value) { this.failedNum = value; }
    public Long getUnregisteredNum() { return unregisteredNum; }
    public void setUnregisteredNum(Long value) { this.unregisteredNum = value; }
    public Integer getUsedAccountCount() { return usedAccountCount; }
    public void setUsedAccountCount(Integer value) { this.usedAccountCount = value; }
    public Integer getInvalidAccountCount() { return invalidAccountCount; }
    public void setInvalidAccountCount(Integer value) { this.invalidAccountCount = value; }
    public Integer getClickUvNum() { return clickUvNum; }
    public void setClickUvNum(Integer value) { this.clickUvNum = value; }
    public Long getClickTotal() { return clickTotal; }
    public void setClickTotal(Long value) { this.clickTotal = value; }
    public Integer getActualConcurrency() { return actualConcurrency; }
    public void setActualConcurrency(Integer value) { this.actualConcurrency = value; }
    public Long getExecutionDurationSec() { return executionDurationSec; }
    public void setExecutionDurationSec(Long value) { this.executionDurationSec = value; }
    public Long getActiveSinceAt() { return activeSinceAt; }
    public void setActiveSinceAt(Long value) { this.activeSinceAt = value; }
    public Long getMetricsUpdatedAt() { return metricsUpdatedAt; }
    public void setMetricsUpdatedAt(Long value) { this.metricsUpdatedAt = value; }
}
