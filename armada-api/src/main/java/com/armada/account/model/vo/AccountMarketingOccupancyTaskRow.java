package com.armada.account.model.vo;

/**
 * 账号列表当前页占用任务状态投影。
 *
 * <p>账号分页只读取 {@code account_group} 的锁事实；Service 再按当前页去重任务 ID
 * 一次性读取本投影，用于派生“暂停占用”和“待释放”标签。</p>
 */
public class AccountMarketingOccupancyTaskRow {

    /** 账号分组 ID；当前页任务批量查询时为空。 */
    private Long groupId;

    /** 分组持久化营销占用类型；当前页任务批量查询时为空。 */
    private Integer occupancyType;

    /** 统一营销任务 ID。 */
    private Long taskId;

    /** 统一营销任务业务类型。 */
    private Integer taskBusinessType;

    /** 统一营销任务名称。 */
    private String taskName;

    /** 统一营销任务主状态。 */
    private Integer taskStatus;

    /** 拉群营销资源状态；普通营销任务为空。 */
    private Integer resourceStatus;

    /** Mapper 按任务状态派生的占用展示覆盖类型：暂停占用或待释放。 */
    private String occupancyOverrideType;

    /** 分组锁定时间(epoch 毫秒)。 */
    private Long lockedAt;

    /** 营销账号总数。 */
    private Integer marketingAccountTotalCount;

    /** 实际调用营销账号数。 */
    private Integer marketingAccountUsedCount;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Integer getOccupancyType() {
        return occupancyType;
    }

    public void setOccupancyType(Integer occupancyType) {
        this.occupancyType = occupancyType;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Integer getTaskBusinessType() {
        return taskBusinessType;
    }

    public void setTaskBusinessType(Integer taskBusinessType) {
        this.taskBusinessType = taskBusinessType;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Integer getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Integer taskStatus) {
        this.taskStatus = taskStatus;
    }

    public Integer getResourceStatus() {
        return resourceStatus;
    }

    public void setResourceStatus(Integer resourceStatus) {
        this.resourceStatus = resourceStatus;
    }

    public String getOccupancyOverrideType() {
        return occupancyOverrideType;
    }

    public void setOccupancyOverrideType(String occupancyOverrideType) {
        this.occupancyOverrideType = occupancyOverrideType;
    }

    public Long getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Long lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Integer getMarketingAccountTotalCount() {
        return marketingAccountTotalCount;
    }

    public void setMarketingAccountTotalCount(Integer marketingAccountTotalCount) {
        this.marketingAccountTotalCount = marketingAccountTotalCount;
    }

    public Integer getMarketingAccountUsedCount() {
        return marketingAccountUsedCount;
    }

    public void setMarketingAccountUsedCount(Integer marketingAccountUsedCount) {
        this.marketingAccountUsedCount = marketingAccountUsedCount;
    }
}
