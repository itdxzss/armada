package com.armada.group.model.entity;

/**
 * 历史群拉人执行的成员明细。
 *
 * <p>联系方式、加群和发送各自保存一次性结果；命令号和结果事件号用于拒绝重复回执。</p>
 */
public class HistoricalGroupPullMember {

    private Long id;
    private Long tenantId;
    private Long executionId;
    private Integer lineNo;
    private String phone;
    private Integer materialType;
    private Long accountId;
    private String protocolAccountIdSnapshot;
    private Integer contactStatus;
    private String contactErrorCode;
    private String contactErrorMessage;
    private Integer addStatus;
    private String addErrorCode;
    private String addErrorMessage;
    private Integer sendStatus;
    private String sendCommandId;
    private String sendResultEventId;
    private String sendErrorCode;
    private String sendErrorMessage;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Integer getMaterialType() { return materialType; }
    public void setMaterialType(Integer materialType) { this.materialType = materialType; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getProtocolAccountIdSnapshot() { return protocolAccountIdSnapshot; }
    public void setProtocolAccountIdSnapshot(String value) { this.protocolAccountIdSnapshot = value; }
    public Integer getContactStatus() { return contactStatus; }
    public void setContactStatus(Integer contactStatus) { this.contactStatus = contactStatus; }
    public String getContactErrorCode() { return contactErrorCode; }
    public void setContactErrorCode(String value) { this.contactErrorCode = value; }
    public String getContactErrorMessage() { return contactErrorMessage; }
    public void setContactErrorMessage(String value) { this.contactErrorMessage = value; }
    public Integer getAddStatus() { return addStatus; }
    public void setAddStatus(Integer addStatus) { this.addStatus = addStatus; }
    public String getAddErrorCode() { return addErrorCode; }
    public void setAddErrorCode(String value) { this.addErrorCode = value; }
    public String getAddErrorMessage() { return addErrorMessage; }
    public void setAddErrorMessage(String value) { this.addErrorMessage = value; }
    public Integer getSendStatus() { return sendStatus; }
    public void setSendStatus(Integer sendStatus) { this.sendStatus = sendStatus; }
    public String getSendCommandId() { return sendCommandId; }
    public void setSendCommandId(String sendCommandId) { this.sendCommandId = sendCommandId; }
    public String getSendResultEventId() { return sendResultEventId; }
    public void setSendResultEventId(String value) { this.sendResultEventId = value; }
    public String getSendErrorCode() { return sendErrorCode; }
    public void setSendErrorCode(String value) { this.sendErrorCode = value; }
    public String getSendErrorMessage() { return sendErrorMessage; }
    public void setSendErrorMessage(String value) { this.sendErrorMessage = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
