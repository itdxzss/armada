package com.armada.hyperlink.task.model.entity;

/** 一轮中冻结的账号集合和稳定顺序。 */
public class HyperlinkTaskRoundAccount {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long hyperlinkTaskRoundId;
    private Long roundNo;
    private Long taskAccountUsageId;
    private Long accountId;
    private Integer selectionNo;
    private Integer assignmentStatus;
    private Long selectedAt;
    private Long lastDispatchAt;
    private Long releasedAt;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getHyperlinkTaskRoundId() { return hyperlinkTaskRoundId; }
    public void setHyperlinkTaskRoundId(Long value) { this.hyperlinkTaskRoundId = value; }
    public Long getRoundNo() { return roundNo; }
    public void setRoundNo(Long value) { this.roundNo = value; }
    public Long getTaskAccountUsageId() { return taskAccountUsageId; }
    public void setTaskAccountUsageId(Long value) { this.taskAccountUsageId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
    public Integer getSelectionNo() { return selectionNo; }
    public void setSelectionNo(Integer value) { this.selectionNo = value; }
    public Integer getAssignmentStatus() { return assignmentStatus; }
    public void setAssignmentStatus(Integer value) { this.assignmentStatus = value; }
    public Long getSelectedAt() { return selectedAt; }
    public void setSelectedAt(Long value) { this.selectedAt = value; }
    public Long getLastDispatchAt() { return lastDispatchAt; }
    public void setLastDispatchAt(Long value) { this.lastDispatchAt = value; }
    public Long getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Long value) { this.releasedAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
