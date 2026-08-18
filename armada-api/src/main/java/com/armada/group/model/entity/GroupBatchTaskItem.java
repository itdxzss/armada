package com.armada.group.model.entity;

/** 群组列表批量刷新任务逐群明细实体。 */
public class GroupBatchTaskItem {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 所属批量任务 ID。 */
    private Long taskId;

    /** 目标群入口 ID。 */
    private Long groupLinkId;

    /** 目标群 JID。 */
    private String groupJid;

    /** 实际执行账号 ID。 */
    private Long accountId;

    /** 明细状态稳定码。 */
    private Integer status;

    /** 失败稳定错误码。 */
    private String errorCode;

    /** 成功说明或脱敏失败原因。 */
    private String description;

    /** 该项结束时间(epoch 毫秒)。 */
    private Long operatedAt;

    /** 当前等待结算的群快照命令 ID。 */
    private String currentCommandId;

    /** 已派发群快照命令次数。 */
    private Integer attemptCount;

    /** 已消费执行账号候选位置。 */
    private Integer candidateCursor;

    /** 当前命令结果超时水位(epoch 毫秒)。 */
    private Long resultDeadlineAt;

    /** 已成功落库 scope 位掩码；1=METADATA，2=INVITE_CODE。 */
    private Integer completedScopeMask;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
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

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getOperatedAt() {
        return operatedAt;
    }

    public void setOperatedAt(Long operatedAt) {
        this.operatedAt = operatedAt;
    }

    public String getCurrentCommandId() {
        return currentCommandId;
    }

    public void setCurrentCommandId(String currentCommandId) {
        this.currentCommandId = currentCommandId;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getCandidateCursor() {
        return candidateCursor;
    }

    public void setCandidateCursor(Integer candidateCursor) {
        this.candidateCursor = candidateCursor;
    }

    public Long getResultDeadlineAt() {
        return resultDeadlineAt;
    }

    public void setResultDeadlineAt(Long resultDeadlineAt) {
        this.resultDeadlineAt = resultDeadlineAt;
    }

    public Integer getCompletedScopeMask() {
        return completedScopeMask;
    }

    public void setCompletedScopeMask(Integer completedScopeMask) {
        this.completedScopeMask = completedScopeMask;
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
