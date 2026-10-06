package com.armada.task.model.entity;

/** 建群者一次性注销账本；保存不可变目标及注销后独立核验证据。 */
public class PullTaskCreatorDeletion {
    /** 主键。 */
    private Long id;
    /** 所属租户。 */
    private Long tenantId;
    /** 任务。 */
    private Long taskId;
    /** 冻结执行行。 */
    private Long groupExecutionId;
    /** 建群时冻结的真实账号。 */
    private Long creatorAccountId;
    /** 建群账号身份 SHA256。 */
    private String creatorIdentityHash;
    /** 冻结协议账号路由。 */
    private String creatorProtocolAccountId;
    /** 冻结的原建群号码。 */
    private String creatorPhone;
    /** 绑定的建群操作。 */
    private String createOperationId;
    /** 注销操作唯一键，全程不变。 */
    private String operationId;
    /** 注销状态，见 PullTaskCreatorDeletionStatus。 */
    private Integer status;
    /** 本次接管管理号。 */
    private Long managerAccountId;
    /** 核验目标群。 */
    private String groupJid;
    /** 注销前协议 Creation 时间。 */
    private Long creationBefore;
    /** 观察次数；不代表删除发送次数。 */
    private Integer attempts;
    /** 协议持久删除结果。 */
    private String deletionResultStatus;
    /** 协议结果绑定操作。 */
    private String resultOperationId;
    /** 协议结果绑定账号。 */
    private String resultIdentityHash;
    /** 最近一次新鲜协议查询证据。 */
    private String evidenceJson;
    /** 证据采集时间。 */
    private Long observedAt;
    /** 已持久化唯一发送意图时间。 */
    private Long submittedAt;
    /** 本轮验证等待截止时间。 */
    private Long deadlineAt;
    /** 全部放行条件满足时间。 */
    private Long completedAt;
    /** 阻断原因。 */
    private String reasonCode;
    /** 运营可读原因。 */
    private String reasonMessage;
    /** 创建时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getGroupExecutionId() { return groupExecutionId; }
    public void setGroupExecutionId(Long groupExecutionId) { this.groupExecutionId = groupExecutionId; }
    public Long getCreatorAccountId() { return creatorAccountId; }
    public void setCreatorAccountId(Long creatorAccountId) { this.creatorAccountId = creatorAccountId; }
    public String getCreatorIdentityHash() { return creatorIdentityHash; }
    public void setCreatorIdentityHash(String creatorIdentityHash) { this.creatorIdentityHash = creatorIdentityHash; }
    public String getCreatorProtocolAccountId() { return creatorProtocolAccountId; }
    public void setCreatorProtocolAccountId(String creatorProtocolAccountId) { this.creatorProtocolAccountId = creatorProtocolAccountId; }
    public String getCreatorPhone() { return creatorPhone; }
    public void setCreatorPhone(String creatorPhone) { this.creatorPhone = creatorPhone; }
    public String getCreateOperationId() { return createOperationId; }
    public void setCreateOperationId(String createOperationId) { this.createOperationId = createOperationId; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getManagerAccountId() { return managerAccountId; }
    public void setManagerAccountId(Long managerAccountId) { this.managerAccountId = managerAccountId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public Long getCreationBefore() { return creationBefore; }
    public void setCreationBefore(Long creationBefore) { this.creationBefore = creationBefore; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public String getDeletionResultStatus() { return deletionResultStatus; }
    public void setDeletionResultStatus(String deletionResultStatus) { this.deletionResultStatus = deletionResultStatus; }
    public String getResultOperationId() { return resultOperationId; }
    public void setResultOperationId(String resultOperationId) { this.resultOperationId = resultOperationId; }
    public String getResultIdentityHash() { return resultIdentityHash; }
    public void setResultIdentityHash(String resultIdentityHash) { this.resultIdentityHash = resultIdentityHash; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public Long getObservedAt() { return observedAt; }
    public void setObservedAt(Long observedAt) { this.observedAt = observedAt; }
    public Long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Long submittedAt) { this.submittedAt = submittedAt; }
    public Long getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Long deadlineAt) { this.deadlineAt = deadlineAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonMessage() { return reasonMessage; }
    public void setReasonMessage(String reasonMessage) { this.reasonMessage = reasonMessage; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
