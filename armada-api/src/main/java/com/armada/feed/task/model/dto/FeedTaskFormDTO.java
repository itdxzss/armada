package com.armada.feed.task.model.dto;

/** 动态发布任务表单。multipart/form-data 通过 setter 绑定。 */
public class FeedTaskFormDTO {

    private String name;
    private String accountFilter;
    private String title;
    private String description;
    private String content;
    private String promotionLink;
    private String textColor;
    private String backgroundColor;
    private Integer taskDelayMinutes;
    private Integer status;
    private Integer concurrency;
    private Integer retryMax;
    private String startMode;
    private String taskMode;
    private String taskPlannedEndAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccountFilter() {
        return accountFilter;
    }

    public void setAccountFilter(String accountFilter) {
        this.accountFilter = accountFilter;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPromotionLink() {
        return promotionLink;
    }

    public void setPromotionLink(String promotionLink) {
        this.promotionLink = promotionLink;
    }

    public String getTextColor() {
        return textColor;
    }

    public void setTextColor(String textColor) {
        this.textColor = textColor;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public Integer getTaskDelayMinutes() {
        return taskDelayMinutes;
    }

    public void setTaskDelayMinutes(Integer taskDelayMinutes) {
        this.taskDelayMinutes = taskDelayMinutes;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
    }

    public Integer getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Integer retryMax) {
        this.retryMax = retryMax;
    }

    public String getStartMode() {
        return startMode;
    }

    public void setStartMode(String startMode) {
        this.startMode = startMode;
    }

    public String getTaskMode() {
        return taskMode;
    }

    public void setTaskMode(String taskMode) {
        this.taskMode = taskMode;
    }

    public String getTaskPlannedEndAt() {
        return taskPlannedEndAt;
    }

    public void setTaskPlannedEndAt(String taskPlannedEndAt) {
        this.taskPlannedEndAt = taskPlannedEndAt;
    }
}
