package com.armada.task.model.entity;

/** 租户拉群营销全局设置实体，映射 {@code pull_task_group_marketing_setting}。 */
public class PullTaskGroupMarketingSetting {

    /** 租户 ID。 */
    private Long tenantId;
    /** 营销静默时间，单位为分钟。 */
    private Integer marketingSilenceMinutes;
    /** 群组封控时间，单位为分钟。 */
    private Integer groupLockdownMinutes;
    /** 单群营销账号上限。 */
    private Integer maxMarketingAccountsPerGroup;
    /** 创建人用户 ID。 */
    private Long createdBy;
    /** 最近修改人用户 ID。 */
    private Long updatedBy;
    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;
    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getMarketingSilenceMinutes() {
        return marketingSilenceMinutes;
    }

    public void setMarketingSilenceMinutes(Integer marketingSilenceMinutes) {
        this.marketingSilenceMinutes = marketingSilenceMinutes;
    }

    public Integer getGroupLockdownMinutes() {
        return groupLockdownMinutes;
    }

    public void setGroupLockdownMinutes(Integer groupLockdownMinutes) {
        this.groupLockdownMinutes = groupLockdownMinutes;
    }

    public Integer getMaxMarketingAccountsPerGroup() {
        return maxMarketingAccountsPerGroup;
    }

    public void setMaxMarketingAccountsPerGroup(Integer maxMarketingAccountsPerGroup) {
        this.maxMarketingAccountsPerGroup = maxMarketingAccountsPerGroup;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
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
