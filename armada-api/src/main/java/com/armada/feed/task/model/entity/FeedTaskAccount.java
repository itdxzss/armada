package com.armada.feed.task.model.entity;

/** 动态发布任务账号明细行。 */
public class FeedTaskAccount {

    private Long id;
    private Long tenantId;
    private Long taskId;
    private Long accountId;
    private String accountPhoneSnapshot;
    private String sendStatus;
    private Integer retryNum;
    private Integer retryMax;
    private String commandId;
    private String protocolMessageId;
    private Long sendAt;
    private Long successAt;
    private Long failedAt;
    private String failCode;
    private String failReason;
    private Long roundNo;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getAccountPhoneSnapshot() { return accountPhoneSnapshot; }
    public void setAccountPhoneSnapshot(String accountPhoneSnapshot) { this.accountPhoneSnapshot = accountPhoneSnapshot; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public Integer getRetryNum() { return retryNum; }
    public void setRetryNum(Integer retryNum) { this.retryNum = retryNum; }
    public Integer getRetryMax() { return retryMax; }
    public void setRetryMax(Integer retryMax) { this.retryMax = retryMax; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getProtocolMessageId() { return protocolMessageId; }
    public void setProtocolMessageId(String protocolMessageId) { this.protocolMessageId = protocolMessageId; }
    public Long getSendAt() { return sendAt; }
    public void setSendAt(Long sendAt) { this.sendAt = sendAt; }
    public Long getSuccessAt() { return successAt; }
    public void setSuccessAt(Long successAt) { this.successAt = successAt; }
    public Long getFailedAt() { return failedAt; }
    public void setFailedAt(Long failedAt) { this.failedAt = failedAt; }
    public String getFailCode() { return failCode; }
    public void setFailCode(String failCode) { this.failCode = failCode; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Long getRoundNo() { return roundNo; }
    public void setRoundNo(Long roundNo) { this.roundNo = roundNo; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
