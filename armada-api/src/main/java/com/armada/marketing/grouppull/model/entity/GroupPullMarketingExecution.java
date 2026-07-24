package com.armada.marketing.grouppull.model.entity;

/** 一个建群账号在拉群营销任务中的唯一执行记录。 */
public class GroupPullMarketingExecution {

    /** 主键 ID。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 统一营销任务 ID。 */
    private Long taskId;

    /** 建群账号 ID。 */
    private Long builderAccountId;

    /** 当前匹配的营销账号 ID。 */
    private Long marketingAccountId;

    /** 正式建群前冻结的群名称。 */
    private String groupName;

    /** WhatsApp 群 JID。 */
    private String groupJid;

    /** 统一群入口 ID。 */
    private Long groupLinkId;

    /** 群邀请链接。 */
    private String groupInviteUrl;

    /** 当前执行状态码。 */
    private Integer executionStatus;

    /** 当前执行阶段码。 */
    private Integer currentStage;

    /** 当前阶段已发生的业务重试次数。 */
    private Integer stageRetryCount;

    /** 下次业务推进时间或短租约到期时间。 */
    private Long nextExecuteAt;

    /** 当前群状态码；群未创建时为空。 */
    private Integer groupStatus;

    /** 查询到的群成员总数。 */
    private Integer groupMemberCount;

    /** 营销账号管理员设置状态码。 */
    private Integer marketerAdminStatus;

    /** 建群账号退群状态码。 */
    private Integer builderExitStatus;

    /** 当前群对应的营销固定目标 ID。 */
    private Long marketingTargetId;

    /** 非致命异常或最终失败原因。 */
    private String failureReason;

    /** 群实际创建成功时间。 */
    private Long groupCreatedAt;

    /** 本次执行收口时间。 */
    private Long finishedAt;

    /** 建群账号任务占用释放时间。 */
    private Long releasedAt;

    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** 更新时间，epoch 毫秒。 */
    private Long updatedAt;

    /** 活动建群账号生成列，只读。 */
    private Long activeBuilderAccountId;

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

    public Long getBuilderAccountId() {
        return builderAccountId;
    }

    public void setBuilderAccountId(Long builderAccountId) {
        this.builderAccountId = builderAccountId;
    }

    public Long getMarketingAccountId() {
        return marketingAccountId;
    }

    public void setMarketingAccountId(Long marketingAccountId) {
        this.marketingAccountId = marketingAccountId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupInviteUrl() {
        return groupInviteUrl;
    }

    public void setGroupInviteUrl(String groupInviteUrl) {
        this.groupInviteUrl = groupInviteUrl;
    }

    public Integer getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(Integer executionStatus) {
        this.executionStatus = executionStatus;
    }

    public Integer getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(Integer currentStage) {
        this.currentStage = currentStage;
    }

    public Integer getStageRetryCount() {
        return stageRetryCount;
    }

    public void setStageRetryCount(Integer stageRetryCount) {
        this.stageRetryCount = stageRetryCount;
    }

    public Long getNextExecuteAt() {
        return nextExecuteAt;
    }

    public void setNextExecuteAt(Long nextExecuteAt) {
        this.nextExecuteAt = nextExecuteAt;
    }

    public Integer getGroupStatus() {
        return groupStatus;
    }

    public void setGroupStatus(Integer groupStatus) {
        this.groupStatus = groupStatus;
    }

    public Integer getGroupMemberCount() {
        return groupMemberCount;
    }

    public void setGroupMemberCount(Integer groupMemberCount) {
        this.groupMemberCount = groupMemberCount;
    }

    public Integer getMarketerAdminStatus() {
        return marketerAdminStatus;
    }

    public void setMarketerAdminStatus(Integer marketerAdminStatus) {
        this.marketerAdminStatus = marketerAdminStatus;
    }

    public Integer getBuilderExitStatus() {
        return builderExitStatus;
    }

    public void setBuilderExitStatus(Integer builderExitStatus) {
        this.builderExitStatus = builderExitStatus;
    }

    public Long getMarketingTargetId() {
        return marketingTargetId;
    }

    public void setMarketingTargetId(Long marketingTargetId) {
        this.marketingTargetId = marketingTargetId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Long getGroupCreatedAt() {
        return groupCreatedAt;
    }

    public void setGroupCreatedAt(Long groupCreatedAt) {
        this.groupCreatedAt = groupCreatedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Long releasedAt) {
        this.releasedAt = releasedAt;
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

    public Long getActiveBuilderAccountId() {
        return activeBuilderAccountId;
    }

    public void setActiveBuilderAccountId(Long activeBuilderAccountId) {
        this.activeBuilderAccountId = activeBuilderAccountId;
    }
}
