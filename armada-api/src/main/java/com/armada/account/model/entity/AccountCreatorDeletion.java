package com.armada.account.model.entity;

/** 一次性建群账号冻结身份和永久生命周期，日志不得输出此对象的号码。 */
public class AccountCreatorDeletion {
    /** 真实建群账号。 */
    private Long accountId;
    /** 冻结租户。 */
    private Long tenantId;
    /** 冻结任务。 */
    private Long taskId;
    /** 冻结执行行。 */
    private Long groupExecutionId;
    /** 规范化身份 SHA256。 */
    private String identityHash;
    /** 冻结规范化号码。 */
    private String creatorPhone;
    /** 冻结协议路由。 */
    private String protocolAccountId;
    /** 冻结建群操作。 */
    private String createOperationId;
    /** 唯一注销操作。 */
    private String operationId;
    /** 生命周期。 */
    private String lifecycle;
    /** 预留时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    /** 证据确认完成时间。 */
    private Long completedAt;
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { this.taskId = value; }
    public Long getGroupExecutionId() { return groupExecutionId; }
    public void setGroupExecutionId(Long value) { this.groupExecutionId = value; }
    public String getIdentityHash() { return identityHash; }
    public void setIdentityHash(String value) { this.identityHash = value; }
    public String getCreatorPhone() { return creatorPhone; }
    public void setCreatorPhone(String value) { this.creatorPhone = value; }
    public String getProtocolAccountId() { return protocolAccountId; }
    public void setProtocolAccountId(String value) { this.protocolAccountId = value; }
    public String getCreateOperationId() { return createOperationId; }
    public void setCreateOperationId(String value) { this.createOperationId = value; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String value) { this.operationId = value; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String value) { this.lifecycle = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long value) { this.completedAt = value; }
}
