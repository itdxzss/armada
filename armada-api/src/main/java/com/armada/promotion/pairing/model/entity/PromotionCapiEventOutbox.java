package com.armada.promotion.pairing.model.entity;

/** promotion_capi_event_outbox 表实体；敏感匹配字段在终态清理。 */
public class PromotionCapiEventOutbox {

    private Long id;
    private Long tenantId;
    private Long ownerUserId;
    private Long promotionChannelId;
    private Long pairingSessionId;
    private Integer eventStage;
    private String eventName;
    private String eventId;
    private Long eventTime;
    private String phoneSha256;
    private String clientIp;
    private String clientUserAgent;
    private String fbp;
    private String fbc;
    private String eventSourceUrl;
    private Integer status;
    private Integer retryCount;
    private Long nextRetryAt;
    private String lockedBy;
    private Long lockedAt;
    private Long sentAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long sensitiveExpiresAt;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getPromotionChannelId() { return promotionChannelId; }
    public void setPromotionChannelId(Long promotionChannelId) { this.promotionChannelId = promotionChannelId; }
    public Long getPairingSessionId() { return pairingSessionId; }
    public void setPairingSessionId(Long pairingSessionId) { this.pairingSessionId = pairingSessionId; }
    public Integer getEventStage() { return eventStage; }
    public void setEventStage(Integer eventStage) { this.eventStage = eventStage; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }
    public String getPhoneSha256() { return phoneSha256; }
    public void setPhoneSha256(String phoneSha256) { this.phoneSha256 = phoneSha256; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getClientUserAgent() { return clientUserAgent; }
    public void setClientUserAgent(String clientUserAgent) { this.clientUserAgent = clientUserAgent; }
    public String getFbp() { return fbp; }
    public void setFbp(String fbp) { this.fbp = fbp; }
    public String getFbc() { return fbc; }
    public void setFbc(String fbc) { this.fbc = fbc; }
    public String getEventSourceUrl() { return eventSourceUrl; }
    public void setEventSourceUrl(String eventSourceUrl) { this.eventSourceUrl = eventSourceUrl; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Long nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public Long getLockedAt() { return lockedAt; }
    public void setLockedAt(Long lockedAt) { this.lockedAt = lockedAt; }
    public Long getSentAt() { return sentAt; }
    public void setSentAt(Long sentAt) { this.sentAt = sentAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public Long getSensitiveExpiresAt() { return sensitiveExpiresAt; }
    public void setSensitiveExpiresAt(Long sensitiveExpiresAt) { this.sensitiveExpiresAt = sensitiveExpiresAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
