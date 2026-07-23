package com.armada.marketing.grouppull.model.entity;

/** 拉群营销任务特有配置，映射 group_pull_marketing_task。 */
public class GroupPullMarketingTask {

    /** 统一营销任务 ID，同时是本表主键。 */
    private Long marketingTaskId;

    /** 租户 ID。 */
    private Long tenantId;

    /** 建群账号来源分组 ID。 */
    private Long builderGroupId;

    /** 建群成功账号转入分组 ID。 */
    private Long successGroupId;

    /** 建群失败账号转入分组 ID。 */
    private Long failureGroupId;

    /** 单个营销账号在当前任务内最大进群数。 */
    private Integer marketingAccountGroupLimit;

    /** 群名前缀；为空时使用任务名称。 */
    private String groupNamePrefix;

    /** 加好友失败后的重试次数，不包含首次。 */
    private Integer friendRetryLimit;

    /** 每个群组抽取的料子数量。 */
    private Integer materialPerGroup;

    /** 群组发言权限操作码。 */
    private Integer speakPermission;

    /** 建群账号完成后是否退出群组。 */
    private Boolean builderExitEnabled;

    /** 当前执行阻塞原因码。 */
    private Integer blockReason;

    /** 当前资源状态码。 */
    private Integer resourceStatus;

    /** 启动锁组时营销账号总数。 */
    private Integer marketingAccountTotalCount;

    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** 更新时间，epoch 毫秒。 */
    private Long updatedAt;

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getBuilderGroupId() {
        return builderGroupId;
    }

    public void setBuilderGroupId(Long builderGroupId) {
        this.builderGroupId = builderGroupId;
    }

    public Long getSuccessGroupId() {
        return successGroupId;
    }

    public void setSuccessGroupId(Long successGroupId) {
        this.successGroupId = successGroupId;
    }

    public Long getFailureGroupId() {
        return failureGroupId;
    }

    public void setFailureGroupId(Long failureGroupId) {
        this.failureGroupId = failureGroupId;
    }

    public Integer getMarketingAccountGroupLimit() {
        return marketingAccountGroupLimit;
    }

    public void setMarketingAccountGroupLimit(Integer marketingAccountGroupLimit) {
        this.marketingAccountGroupLimit = marketingAccountGroupLimit;
    }

    public String getGroupNamePrefix() {
        return groupNamePrefix;
    }

    public void setGroupNamePrefix(String groupNamePrefix) {
        this.groupNamePrefix = groupNamePrefix;
    }

    public Integer getFriendRetryLimit() {
        return friendRetryLimit;
    }

    public void setFriendRetryLimit(Integer friendRetryLimit) {
        this.friendRetryLimit = friendRetryLimit;
    }

    public Integer getMaterialPerGroup() {
        return materialPerGroup;
    }

    public void setMaterialPerGroup(Integer materialPerGroup) {
        this.materialPerGroup = materialPerGroup;
    }

    public Integer getSpeakPermission() {
        return speakPermission;
    }

    public void setSpeakPermission(Integer speakPermission) {
        this.speakPermission = speakPermission;
    }

    public Boolean getBuilderExitEnabled() {
        return builderExitEnabled;
    }

    public void setBuilderExitEnabled(Boolean builderExitEnabled) {
        this.builderExitEnabled = builderExitEnabled;
    }

    public Integer getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(Integer blockReason) {
        this.blockReason = blockReason;
    }

    public Integer getResourceStatus() {
        return resourceStatus;
    }

    public void setResourceStatus(Integer resourceStatus) {
        this.resourceStatus = resourceStatus;
    }

    public Integer getMarketingAccountTotalCount() {
        return marketingAccountTotalCount;
    }

    public void setMarketingAccountTotalCount(Integer marketingAccountTotalCount) {
        this.marketingAccountTotalCount = marketingAccountTotalCount;
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
