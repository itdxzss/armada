package com.armada.group.model.entity;

/** 群组列表批量刷新任务主表实体。 */
public class GroupBatchTask {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 归属用户 ID；历史待分配数据为 null。 */
    private Long ownerUserId;

    /** 批量操作类型稳定码。 */
    private Integer taskType;

    /** 任务主状态稳定码。 */
    private Integer status;

    /** 提交校验去重后的有效处理项数。 */
    private Integer totalCount;

    /** 成功项数。 */
    private Integer successCount;

    /** 失败项数。 */
    private Integer failedCount;

    /** 前端幂等键。 */
    private String requestId;

    /** 发起操作的用户 ID。 */
    private Long createdBy;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 进入终态时间(epoch 毫秒)。 */
    private Long completedAt;

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

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Integer getTaskType() {
        return taskType;
    }

    public void setTaskType(Integer taskType) {
        this.taskType = taskType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Integer successCount) {
        this.successCount = successCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long completedAt) {
        this.completedAt = completedAt;
    }
}
