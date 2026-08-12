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

    /** 获取最新群信息提交时冻结的群详情同步成功时间;超过该值即判定本项已刷新。 */
    private Long baselineSyncedAt;

    /** 该项结束时间(epoch 毫秒)。 */
    private Long operatedAt;

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

    public Long getBaselineSyncedAt() {
        return baselineSyncedAt;
    }

    public void setBaselineSyncedAt(Long baselineSyncedAt) {
        this.baselineSyncedAt = baselineSyncedAt;
    }

    public Long getOperatedAt() {
        return operatedAt;
    }

    public void setOperatedAt(Long operatedAt) {
        this.operatedAt = operatedAt;
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
