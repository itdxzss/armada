package com.armada.hyperlink.task.model.entity;

import java.math.BigDecimal;

/** 一任务一行的本地计费 Saga 状态。 */
public class HyperlinkBillingReservation {
    private Long id;
    private Long tenantId;
    private Long hyperlinkTaskId;
    private String billingProvider;
    private String quoteId;
    private Long quoteExpiresAt;
    private String priceCode;
    private Integer pricingMode;
    private String currencyCode;
    private BigDecimal unitPrice;
    private String pricingBreakdown;
    private Integer quotedRecipientCount;
    private BigDecimal quotedAmount;
    private BigDecimal reservedAmount;
    private BigDecimal settledAmount;
    private BigDecimal releasedAmount;
    private Long settledSendCount;
    private Integer reservationStatus;
    private Integer pendingOperation;
    private String operationIdempotencyKey;
    private Long nextRetryAt;
    private String externalReservationNo;
    private String failureCode;
    private String failureReason;
    private Long reservedAt;
    private Long settledAt;
    private Long releasedAt;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public String getBillingProvider() { return billingProvider; }
    public void setBillingProvider(String value) { this.billingProvider = value; }
    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String value) { this.quoteId = value; }
    public Long getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Long value) { this.quoteExpiresAt = value; }
    public String getPriceCode() { return priceCode; }
    public void setPriceCode(String value) { this.priceCode = value; }
    public Integer getPricingMode() { return pricingMode; }
    public void setPricingMode(Integer value) { this.pricingMode = value; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String value) { this.currencyCode = value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { this.unitPrice = value; }
    public String getPricingBreakdown() { return pricingBreakdown; }
    public void setPricingBreakdown(String value) { this.pricingBreakdown = value; }
    public Integer getQuotedRecipientCount() { return quotedRecipientCount; }
    public void setQuotedRecipientCount(Integer value) { this.quotedRecipientCount = value; }
    public BigDecimal getQuotedAmount() { return quotedAmount; }
    public void setQuotedAmount(BigDecimal value) { this.quotedAmount = value; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(BigDecimal value) { this.reservedAmount = value; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal value) { this.settledAmount = value; }
    public BigDecimal getReleasedAmount() { return releasedAmount; }
    public void setReleasedAmount(BigDecimal value) { this.releasedAmount = value; }
    public Long getSettledSendCount() { return settledSendCount; }
    public void setSettledSendCount(Long value) { this.settledSendCount = value; }
    public Integer getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(Integer value) { this.reservationStatus = value; }
    public Integer getPendingOperation() { return pendingOperation; }
    public void setPendingOperation(Integer value) { this.pendingOperation = value; }
    public String getOperationIdempotencyKey() { return operationIdempotencyKey; }
    public void setOperationIdempotencyKey(String value) { this.operationIdempotencyKey = value; }
    public Long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Long value) { this.nextRetryAt = value; }
    public String getExternalReservationNo() { return externalReservationNo; }
    public void setExternalReservationNo(String value) { this.externalReservationNo = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { this.failureCode = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
    public Long getReservedAt() { return reservedAt; }
    public void setReservedAt(Long value) { this.reservedAt = value; }
    public Long getSettledAt() { return settledAt; }
    public void setSettledAt(Long value) { this.settledAt = value; }
    public Long getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Long value) { this.releasedAt = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
