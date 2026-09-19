package com.armada.account.model.entity;

/** 分组互存Item的持久事实，不存凭据。 */
public class AccountMutualContactItem {
    private Long id;
    private Long tenantId;
    private Long taskId;
    private Long actorId;
    private Long targetId;
    private String actorPhone;
    private String targetPhone;
    private String protocolAccountId;
    private String protocolBackend;
    private Integer status;
    private String commandId;
    private Integer attemptNo;
    private Boolean retryable;
    private String reasonCode;
    private Long submittedAt;
    private Long resultAt;
    private Long nextExecuteAt;
    private Long createdAt;
    private Long updatedAt;
    public Long getId() {
        return id;
    }
    public void setId(Long value) {
        id = value;
    }
    public Long getTenantId() {
        return tenantId;
    }
    public void setTenantId(Long value) {
        tenantId = value;
    }
    public Long getTaskId() {
        return taskId;
    }
    public void setTaskId(Long value) {
        taskId = value;
    }
    public Long getActorId() {
        return actorId;
    }
    public void setActorId(Long value) {
        actorId = value;
    }
    public Long getTargetId() {
        return targetId;
    }
    public void setTargetId(Long value) {
        targetId = value;
    }
    public String getActorPhone() {
        return actorPhone;
    }
    public void setActorPhone(String value) {
        actorPhone = value;
    }
    public String getTargetPhone() {
        return targetPhone;
    }
    public void setTargetPhone(String value) {
        targetPhone = value;
    }
    public String getProtocolAccountId() {
        return protocolAccountId;
    }
    public void setProtocolAccountId(String value) {
        protocolAccountId = value;
    }
    public String getProtocolBackend() {
        return protocolBackend;
    }
    public void setProtocolBackend(String value) {
        protocolBackend = value;
    }
    public Integer getStatus() {
        return status;
    }
    public void setStatus(Integer value) {
        status = value;
    }
    public String getCommandId() {
        return commandId;
    }
    public void setCommandId(String value) {
        commandId = value;
    }
    public Integer getAttemptNo() {
        return attemptNo;
    }
    public void setAttemptNo(Integer value) {
        attemptNo = value;
    }
    public Boolean getRetryable() {
        return retryable;
    }
    public void setRetryable(Boolean value) {
        retryable = value;
    }
    public String getReasonCode() {
        return reasonCode;
    }
    public void setReasonCode(String value) {
        reasonCode = value;
    }
    public Long getSubmittedAt() {
        return submittedAt;
    }
    public void setSubmittedAt(Long value) {
        submittedAt = value;
    }
    public Long getResultAt() {
        return resultAt;
    }
    public void setResultAt(Long value) {
        resultAt = value;
    }
    public Long getNextExecuteAt() {
        return nextExecuteAt;
    }
    public void setNextExecuteAt(Long value) {
        nextExecuteAt = value;
    }
    public Long getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Long value) {
        createdAt = value;
    }
    public Long getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Long value) {
        updatedAt = value;
    }
}
