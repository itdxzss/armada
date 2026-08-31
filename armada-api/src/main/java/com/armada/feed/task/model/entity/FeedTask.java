package com.armada.feed.task.model.entity;

/** 动态发布任务主表行。 */
public class FeedTask {

    private Long id;
    private Long tenantId;
    private String name;
    private String accountFilter;
    private String title;
    private String description;
    private String content;
    private String promotionLink;
    private Long linkPreviewImageFileId;
    private String textColor;
    private String backgroundColor;
    private Integer concurrency;
    private Integer retryMax;
    private String startMode;
    private Integer taskDelayMinutes;
    private Long taskStartAt;
    private String taskMode;
    private Long taskPlannedEndAt;
    private Integer status;
    private Integer taskStatus;
    private Long currentRoundNo;
    private Long nextRunAt;
    private Integer totalAccountNum;
    private Integer successAccountNum;
    private Integer failedAccountNum;
    private Long createdBy;
    private Long createdAt;
    private Long updatedAt;
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAccountFilter() { return accountFilter; }
    public void setAccountFilter(String accountFilter) { this.accountFilter = accountFilter; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getPromotionLink() { return promotionLink; }
    public void setPromotionLink(String promotionLink) { this.promotionLink = promotionLink; }
    public Long getLinkPreviewImageFileId() { return linkPreviewImageFileId; }
    public void setLinkPreviewImageFileId(Long linkPreviewImageFileId) { this.linkPreviewImageFileId = linkPreviewImageFileId; }
    public String getTextColor() { return textColor; }
    public void setTextColor(String textColor) { this.textColor = textColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer concurrency) { this.concurrency = concurrency; }
    public Integer getRetryMax() { return retryMax; }
    public void setRetryMax(Integer retryMax) { this.retryMax = retryMax; }
    public String getStartMode() { return startMode; }
    public void setStartMode(String startMode) { this.startMode = startMode; }
    public Integer getTaskDelayMinutes() { return taskDelayMinutes; }
    public void setTaskDelayMinutes(Integer taskDelayMinutes) { this.taskDelayMinutes = taskDelayMinutes; }
    public Long getTaskStartAt() { return taskStartAt; }
    public void setTaskStartAt(Long taskStartAt) { this.taskStartAt = taskStartAt; }
    public String getTaskMode() { return taskMode; }
    public void setTaskMode(String taskMode) { this.taskMode = taskMode; }
    public Long getTaskPlannedEndAt() { return taskPlannedEndAt; }
    public void setTaskPlannedEndAt(Long taskPlannedEndAt) { this.taskPlannedEndAt = taskPlannedEndAt; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getTaskStatus() { return taskStatus; }
    public void setTaskStatus(Integer taskStatus) { this.taskStatus = taskStatus; }
    public Long getCurrentRoundNo() { return currentRoundNo; }
    public void setCurrentRoundNo(Long currentRoundNo) { this.currentRoundNo = currentRoundNo; }
    public Long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Long nextRunAt) { this.nextRunAt = nextRunAt; }
    public Integer getTotalAccountNum() { return totalAccountNum; }
    public void setTotalAccountNum(Integer totalAccountNum) { this.totalAccountNum = totalAccountNum; }
    public Integer getSuccessAccountNum() { return successAccountNum; }
    public void setSuccessAccountNum(Integer successAccountNum) { this.successAccountNum = successAccountNum; }
    public Integer getFailedAccountNum() { return failedAccountNum; }
    public void setFailedAccountNum(Integer failedAccountNum) { this.failedAccountNum = failedAccountNum; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
