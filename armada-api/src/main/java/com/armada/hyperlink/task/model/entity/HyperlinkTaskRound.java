package com.armada.hyperlink.task.model.entity;

/** 周期任务稳定轮次。 */
public class HyperlinkTaskRound {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long roundNo;
    private Integer roundStatus;
    private Long scheduledAt;
    private Long nextDispatchAt;
    private String leaseOwner;
    private Long leaseExpiresAt;
    private Integer assignedRecipientCount;
    private Integer selectedAccountCount;
    private Integer actualConcurrency;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long readNum;
    private Long failNum;
    private Long fail404Num;
    private Long startedAt;
    private Long dispatchCompletedAt;
    private Long lastSendAt;
    private Long finishedAt;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getRoundNo() { return roundNo; }
    public void setRoundNo(Long value) { this.roundNo = value; }
    public Integer getRoundStatus() { return roundStatus; }
    public void setRoundStatus(Integer value) { this.roundStatus = value; }
    public Long getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Long value) { this.scheduledAt = value; }
    public Long getNextDispatchAt() { return nextDispatchAt; }
    public void setNextDispatchAt(Long value) { this.nextDispatchAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { this.leaseOwner = value; }
    public Long getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Long value) { this.leaseExpiresAt = value; }
    public Integer getAssignedRecipientCount() { return assignedRecipientCount; }
    public void setAssignedRecipientCount(Integer value) { this.assignedRecipientCount = value; }
    public Integer getSelectedAccountCount() { return selectedAccountCount; }
    public void setSelectedAccountCount(Integer value) { this.selectedAccountCount = value; }
    public Integer getActualConcurrency() { return actualConcurrency; }
    public void setActualConcurrency(Integer value) { this.actualConcurrency = value; }
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
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long value) { this.startedAt = value; }
    public Long getDispatchCompletedAt() { return dispatchCompletedAt; }
    public void setDispatchCompletedAt(Long value) { this.dispatchCompletedAt = value; }
    public Long getLastSendAt() { return lastSendAt; }
    public void setLastSendAt(Long value) { this.lastSendAt = value; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long value) { this.finishedAt = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
