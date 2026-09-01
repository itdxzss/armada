package com.armada.platform.protocol.risk.model;

/** 协议风控追加事实表实体。 */
public class ProtocolRiskEvent {
    private Long id;
    private Long tenantId;
    private String eventId;
    private String signalCode;
    private String scopeType;
    private String operationType;
    private Long accountId;
    private String protocolAccountId;
    private String protocolBackend;
    private String source;
    private String businessType;
    private Long businessId;
    private Long businessItemId;
    private Long groupBusinessId;
    private String commandId;
    private String messageId;
    private String targetKind;
    private String chatJid;
    private String rawCode;
    private String reasonMessage;
    private Boolean isActive;
    private String enforcementType;
    private Long restrictedUntil;
    private String traceId;
    private String workerId;
    private Long occurredAt;
    private Long receivedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getSignalCode() { return signalCode; }
    public void setSignalCode(String value) { signalCode = value; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String value) { operationType = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getProtocolAccountId() { return protocolAccountId; }
    public void setProtocolAccountId(String value) { protocolAccountId = value; }
    public String getProtocolBackend() { return protocolBackend; }
    public void setProtocolBackend(String value) { protocolBackend = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String value) { businessType = value; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long value) { businessId = value; }
    public Long getBusinessItemId() { return businessItemId; }
    public void setBusinessItemId(Long value) { businessItemId = value; }
    public Long getGroupBusinessId() { return groupBusinessId; }
    public void setGroupBusinessId(Long value) { groupBusinessId = value; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String value) { commandId = value; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public String getChatJid() { return chatJid; }
    public void setChatJid(String value) { chatJid = value; }
    public String getRawCode() { return rawCode; }
    public void setRawCode(String value) { rawCode = value; }
    public String getReasonMessage() { return reasonMessage; }
    public void setReasonMessage(String value) { reasonMessage = value; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean value) { isActive = value; }
    public String getEnforcementType() { return enforcementType; }
    public void setEnforcementType(String value) { enforcementType = value; }
    public Long getRestrictedUntil() { return restrictedUntil; }
    public void setRestrictedUntil(Long value) { restrictedUntil = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String value) { workerId = value; }
    public Long getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Long value) { occurredAt = value; }
    public Long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Long value) { receivedAt = value; }
}
