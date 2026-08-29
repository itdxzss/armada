package com.armada.hyperlink.task.model.vo;

/** 任务与 runtime 的详情摘要投影。 */
public class HyperlinkTaskSummaryRow {
    private Long id;
    private String taskName;
    private Integer recipientTotal;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long readNum;
    private Long failedNum;
    private Long unregisteredNum;
    private Integer usedAccountCount;
    private Integer invalidAccountCount;
    private Long clickUvNum;
    private Long clickTotal;
    private Integer actualConcurrency;
    private Long executionDurationSec;
    private Integer runStatus;
    private Long activeSinceAt;
    private Long metricsUpdatedAt;
    private Long firstVisitAt;
    private Long lastVisitAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
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
    public Long getClickUvNum() { return clickUvNum; }
    public void setClickUvNum(Long value) { this.clickUvNum = value; }
    public Long getClickTotal() { return clickTotal; }
    public void setClickTotal(Long value) { this.clickTotal = value; }
    public Integer getActualConcurrency() { return actualConcurrency; }
    public void setActualConcurrency(Integer value) { this.actualConcurrency = value; }
    public Long getExecutionDurationSec() { return executionDurationSec; }
    public void setExecutionDurationSec(Long value) { this.executionDurationSec = value; }
    public Integer getRunStatus() { return runStatus; }
    public void setRunStatus(Integer runStatus) { this.runStatus = runStatus; }
    public Long getActiveSinceAt() { return activeSinceAt; }
    public void setActiveSinceAt(Long activeSinceAt) { this.activeSinceAt = activeSinceAt; }
    public Long getMetricsUpdatedAt() { return metricsUpdatedAt; }
    public void setMetricsUpdatedAt(Long value) { this.metricsUpdatedAt = value; }
    public Long getFirstVisitAt() { return firstVisitAt; }
    public void setFirstVisitAt(Long value) { this.firstVisitAt = value; }
    public Long getLastVisitAt() { return lastVisitAt; }
    public void setLastVisitAt(Long value) { this.lastVisitAt = value; }
}
