package com.armada.task.model.entity;

import com.armada.task.model.enums.PullTaskParticipantExecutionState;

/** 一次批量拉人调用中单个料子或站台的不可变执行身份与事实台账。 */
public class PullTaskPullCallMemberAttempt {

    private Long id;
    private Long tenantId;
    private Long taskId;
    private Long groupExecutionId;
    private Long pullCallId;
    private Integer participantType;
    private Long participantRefId;
    private String targetPhone;
    private String targetJid;
    private Long pullerGroupAccountId;
    private Integer attemptNo;
    private Long failureCountBefore;
    private Integer lifecycleStatus;
    private Integer activeSlot;
    private String protocolOutcome;
    private PullTaskParticipantExecutionState executionState;
    private String reasonCode;
    private String reasonMessage;
    private Long submittedAt;
    private Long resultAt;
    private Long releasedAt;
    private Long createdAt;
    private Long updatedAt;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getGroupExecutionId() {
        return groupExecutionId;
    }

    public void setGroupExecutionId(Long groupExecutionId) {
        this.groupExecutionId = groupExecutionId;
    }

    public Long getPullCallId() {
        return pullCallId;
    }

    public void setPullCallId(Long pullCallId) {
        this.pullCallId = pullCallId;
    }

    public Integer getParticipantType() {
        return participantType;
    }

    public void setParticipantType(Integer participantType) {
        this.participantType = participantType;
    }

    public Long getParticipantRefId() {
        return participantRefId;
    }

    public void setParticipantRefId(Long participantRefId) {
        this.participantRefId = participantRefId;
    }

    public String getTargetPhone() {
        return targetPhone;
    }

    public void setTargetPhone(String targetPhone) {
        this.targetPhone = targetPhone;
    }

    public String getTargetJid() {
        return targetJid;
    }

    public void setTargetJid(String targetJid) {
        this.targetJid = targetJid;
    }

    public Long getPullerGroupAccountId() {
        return pullerGroupAccountId;
    }

    public void setPullerGroupAccountId(Long pullerGroupAccountId) {
        this.pullerGroupAccountId = pullerGroupAccountId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public Long getFailureCountBefore() {
        return failureCountBefore;
    }

    public void setFailureCountBefore(Long failureCountBefore) {
        this.failureCountBefore = failureCountBefore;
    }

    public Integer getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(Integer lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public Integer getActiveSlot() {
        return activeSlot;
    }

    public void setActiveSlot(Integer activeSlot) {
        this.activeSlot = activeSlot;
    }

    public String getProtocolOutcome() {
        return protocolOutcome;
    }

    public void setProtocolOutcome(String protocolOutcome) {
        this.protocolOutcome = protocolOutcome;
    }

    public PullTaskParticipantExecutionState getExecutionState() {
        return executionState;
    }

    public void setExecutionState(PullTaskParticipantExecutionState executionState) {
        this.executionState = executionState;
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

    public Long getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Long releasedAt) {
        this.releasedAt = releasedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
