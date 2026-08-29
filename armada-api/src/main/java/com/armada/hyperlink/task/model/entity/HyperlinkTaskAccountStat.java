package com.armada.hyperlink.task.model.entity;

/** 可从 recipient 重建的任务账号累计查询投影。 */
public class HyperlinkTaskAccountStat {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private Long accountId;
    private Long sendTotal;
    private Long successNum;
    private Long deliveredNum;
    private Long readNum;
    private Long failedNum;
    private Long fail404Num;
    private Long firstSendAt;
    private Long lastSendAt;
    private Long createdAt;
    private Long updatedAt;
    private Long reconciledAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
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
    public Long getFail404Num() { return fail404Num; }
    public void setFail404Num(Long value) { this.fail404Num = value; }
    public Long getFirstSendAt() { return firstSendAt; }
    public void setFirstSendAt(Long value) { this.firstSendAt = value; }
    public Long getLastSendAt() { return lastSendAt; }
    public void setLastSendAt(Long value) { this.lastSendAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
    public Long getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Long value) { this.reconciledAt = value; }
}
