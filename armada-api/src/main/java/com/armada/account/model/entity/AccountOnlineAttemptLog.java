package com.armada.account.model.entity;

import java.time.LocalDateTime;

/**
 * 账号上线尝试诊断时间线实体,映射 account_online_attempt_log。
 */
public class AccountOnlineAttemptLog {

    private Long id;
    private Long tenantId;
    private Long accountId;
    private String protocolAccountId;
    private String onlineAttemptId;
    private String previousOnlineAttemptId;
    private String commandId;
    private String batchId;
    private Long proxyId;
    private String source;
    private String fromState;
    private String toState;
    private String diagnosisCode;
    private String diagnosisClass;
    private Integer rawCode;
    private String rawReason;
    private String recoverability;
    private String actionTaken;
    private String workerId;
    private String evidenceJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public String getOnlineAttemptId() {
        return onlineAttemptId;
    }

    public void setOnlineAttemptId(String onlineAttemptId) {
        this.onlineAttemptId = onlineAttemptId;
    }

    public String getPreviousOnlineAttemptId() {
        return previousOnlineAttemptId;
    }

    public void setPreviousOnlineAttemptId(String previousOnlineAttemptId) {
        this.previousOnlineAttemptId = previousOnlineAttemptId;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Long getProxyId() {
        return proxyId;
    }

    public void setProxyId(Long proxyId) {
        this.proxyId = proxyId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFromState() {
        return fromState;
    }

    public void setFromState(String fromState) {
        this.fromState = fromState;
    }

    public String getToState() {
        return toState;
    }

    public void setToState(String toState) {
        this.toState = toState;
    }

    public String getDiagnosisCode() {
        return diagnosisCode;
    }

    public void setDiagnosisCode(String diagnosisCode) {
        this.diagnosisCode = diagnosisCode;
    }

    public String getDiagnosisClass() {
        return diagnosisClass;
    }

    public void setDiagnosisClass(String diagnosisClass) {
        this.diagnosisClass = diagnosisClass;
    }

    public Integer getRawCode() {
        return rawCode;
    }

    public void setRawCode(Integer rawCode) {
        this.rawCode = rawCode;
    }

    public String getRawReason() {
        return rawReason;
    }

    public void setRawReason(String rawReason) {
        this.rawReason = rawReason;
    }

    public String getRecoverability() {
        return recoverability;
    }

    public void setRecoverability(String recoverability) {
        this.recoverability = recoverability;
    }

    public String getActionTaken() {
        return actionTaken;
    }

    public void setActionTaken(String actionTaken) {
        this.actionTaken = actionTaken;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
