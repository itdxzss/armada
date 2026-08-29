package com.armada.hyperlink.task.model.entity;

/** 任务双状态与聚合投影。 */
public class HyperlinkTaskRuntime {
    private Long hyperlinkTaskId;
    private Long tenantId;
    private Boolean enabled;
    private Integer runStatus;
    private Integer provisionStatus;
    private Long currentRoundId;
    private Long currentRoundNo;
    private Long startedAt;
    private Long lastSendAt;
    private Long finishedAt;
    private Long firstVisitAt;
    private Long lastVisitAt;
    private Integer recipientTotal;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long readNum;
    private Long failNum;
    private Long fail404Num;
    private Integer invalidAccountCount;
    private Integer clickUvNum;
    private Long clickTotal;
    private Integer usedAccountCount;
    private Integer actualConcurrency;
    private Long executionDurationSec;
    private Long activeSinceAt;
    private Long metricsUpdatedAt;
    private Integer failureCode;
    private String failureReason;
    private Long createdAt;
    private Long updatedAt;

    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { this.enabled = value; }
    public Integer getRunStatus() { return runStatus; }
    public void setRunStatus(Integer value) { this.runStatus = value; }
    public Integer getProvisionStatus() { return provisionStatus; }
    public void setProvisionStatus(Integer value) { this.provisionStatus = value; }
    public Long getCurrentRoundId() { return currentRoundId; }
    public void setCurrentRoundId(Long value) { this.currentRoundId = value; }
    public Long getCurrentRoundNo() { return currentRoundNo; }
    public void setCurrentRoundNo(Long value) { this.currentRoundNo = value; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long value) { this.startedAt = value; }
    public Long getLastSendAt() { return lastSendAt; }
    public void setLastSendAt(Long value) { this.lastSendAt = value; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long value) { this.finishedAt = value; }
    public Long getFirstVisitAt() { return firstVisitAt; }
    public void setFirstVisitAt(Long value) { this.firstVisitAt = value; }
    public Long getLastVisitAt() { return lastVisitAt; }
    public void setLastVisitAt(Long value) { this.lastVisitAt = value; }
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
    public Long getFailNum() { return failNum; }
    public void setFailNum(Long value) { this.failNum = value; }
    public Long getFail404Num() { return fail404Num; }
    public void setFail404Num(Long value) { this.fail404Num = value; }
    public Integer getInvalidAccountCount() { return invalidAccountCount; }
    public void setInvalidAccountCount(Integer value) { this.invalidAccountCount = value; }
    public Integer getClickUvNum() { return clickUvNum; }
    public void setClickUvNum(Integer value) { this.clickUvNum = value; }
    public Long getClickTotal() { return clickTotal; }
    public void setClickTotal(Long value) { this.clickTotal = value; }
    public Integer getUsedAccountCount() { return usedAccountCount; }
    public void setUsedAccountCount(Integer value) { this.usedAccountCount = value; }
    public Integer getActualConcurrency() { return actualConcurrency; }
    public void setActualConcurrency(Integer value) { this.actualConcurrency = value; }
    public Long getExecutionDurationSec() { return executionDurationSec; }
    public void setExecutionDurationSec(Long value) { this.executionDurationSec = value; }
    public Long getActiveSinceAt() { return activeSinceAt; }
    public void setActiveSinceAt(Long value) { this.activeSinceAt = value; }
    public Long getMetricsUpdatedAt() { return metricsUpdatedAt; }
    public void setMetricsUpdatedAt(Long value) { this.metricsUpdatedAt = value; }
    public Integer getFailureCode() { return failureCode; }
    public void setFailureCode(Integer value) { this.failureCode = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
