package com.armada.marketing.model.entity;

public class MarketingTaskSendAttempt {
    private Long id;
    private Long tenantId;
    private Long marketingTaskId;
    private Long targetId;
    private Long roundNo;
    private Integer attemptNo;
    private Boolean retry;
    private String commandId;
    private Integer status;
    private String reasonCode;
    private String reasonMessage;
    private String messageId;
    private Long submittedAt;
    private Long resultAt;
    private Long attemptedAt;
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Long roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public Boolean getRetry() {
        return retry;
    }

    public void setRetry(Boolean retry) {
        this.retry = retry;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getResultAt() {
        return resultAt;
    }

    public void setResultAt(Long resultAt) {
        this.resultAt = resultAt;
    }

    public Long getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Long attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
