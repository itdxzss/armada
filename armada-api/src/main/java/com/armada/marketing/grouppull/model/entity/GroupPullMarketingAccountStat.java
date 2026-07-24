package com.armada.marketing.grouppull.model.entity;

/** 营销账号当前任务内群额度，映射 group_pull_marketing_account_stat。 */
public class GroupPullMarketingAccountStat {

    /** 主键 ID。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 统一营销任务 ID。 */
    private Long taskId;

    /** 营销账号 ID。 */
    private Long accountId;

    /** 已匹配但尚未确认进群的额度。 */
    private Integer reservedGroupCount;

    /** 已成功进群并永久消耗的额度。 */
    private Integer joinedGroupCount;

    /** 首次实际调用时间，epoch 毫秒。 */
    private Long createdAt;

    /** 最近调用时间，epoch 毫秒。 */
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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getReservedGroupCount() {
        return reservedGroupCount;
    }

    public void setReservedGroupCount(Integer reservedGroupCount) {
        this.reservedGroupCount = reservedGroupCount;
    }

    public Integer getJoinedGroupCount() {
        return joinedGroupCount;
    }

    public void setJoinedGroupCount(Integer joinedGroupCount) {
        this.joinedGroupCount = joinedGroupCount;
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
