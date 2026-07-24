package com.armada.marketing.model.entity;

/**
 * 营销任务公共主表实体，映射 {@code marketing_task}。
 *
 * <p>普通营销和拉群营销共用该实体，通过 {@code businessType} 隔离菜单和生命周期；
 * 拉群营销特有配置不在本实体重复保存。</p>
 */
public class MarketingTask {

    /** 统一营销任务主键。 */
    private Long id;

    /** 数据所属租户 ID。 */
    private Long tenantId;

    /** 任务名称。 */
    private String taskName;

    /** 业务类型：1=普通营销，2=拉群营销。 */
    private Integer businessType;

    /** 执行营销发送的账号分组 ID。 */
    private Long accountGroupId;

    /** 创建任务时的营销账号分组名称快照。 */
    private String accountGroupName;

    /** 营销模板 ID。 */
    private Long marketingTemplateId;

    /** 创建任务时的营销模板名称快照。 */
    private String marketingTemplateName;

    /** 任务主状态码，见 {@code MarketingTaskStatus}。 */
    private Integer status;

    /** 创建任务时选中的去重营销账号数量。 */
    private Integer selectedAccountCount;

    /** 任务累计成功触达的去重群数量。 */
    private Integer targetGroupCount;

    /** 账号与群组组合形成的执行目标行数。 */
    private Integer targetPairCount;

    /** 任务累计发送成功消息数。 */
    private Integer sentMessageCount;

    /** 任务累计发送失败消息数。 */
    private Integer failedMessageCount;

    /** 每个营销轮次发送的消息条数。 */
    private Integer sendPerRound;

    /** 同一营销账号下相邻群组命令的发送间隔（毫秒）。 */
    private Integer accountGroupSendIntervalMs;

    /** 相邻营销轮次之间的间隔（秒）。 */
    private Integer sendIntervalSeconds;

    /** 发送前是否校验营销账号在线。 */
    private Boolean onlineCheckEnabled;

    /** 发送时是否跳过状态异常的群。 */
    private Boolean abnormalGroupSkipped;

    /** 单次发送失败后是否允许自动重试。 */
    private Boolean autoRetryEnabled;

    /** 单条消息允许的最大重试次数。 */
    private Integer retryLimit;

    /** 已成功抢占生成的最新正常营销轮次号。 */
    private Long currentRoundNo;

    /** 任务备注。 */
    private String remark;

    /** 账号动态群目标的群加入时间筛选下界（epoch 毫秒）。 */
    private Long accountGroupSendAt;

    /** 任务计划开始时间（epoch 毫秒）。 */
    private Long taskStartAt;

    /** 任务计划结束时间（epoch 毫秒）。 */
    private Long taskEndAt;

    /** 任务实际首次启动时间（epoch 毫秒）。 */
    private Long startedAt;

    /** 下一轮应生成时间（epoch 毫秒）。 */
    private Long nextRoundAt;

    /** 最近一次正常营销轮次开始生成时间（epoch 毫秒）。 */
    private Long lastRoundStartedAt;

    /** 最近一条消息发送成功时间（epoch 毫秒）。 */
    private Long lastSentAt;

    /** 任务进入完成或关闭终态的时间（epoch 毫秒）。 */
    private Long finishedAt;

    /** 创建任务的用户 ID。 */
    private Long createdBy;

    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;

    /** 最近更新时间（epoch 毫秒）。 */
    private Long updatedAt;

    /** 软删除时间（epoch 毫秒）；为空表示未删除。 */
    private Long deletedAt;

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

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Integer getBusinessType() {
        return businessType;
    }

    public void setBusinessType(Integer businessType) {
        this.businessType = businessType;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getAccountGroupName() {
        return accountGroupName;
    }

    public void setAccountGroupName(String accountGroupName) {
        this.accountGroupName = accountGroupName;
    }

    public Long getMarketingTemplateId() {
        return marketingTemplateId;
    }

    public void setMarketingTemplateId(Long marketingTemplateId) {
        this.marketingTemplateId = marketingTemplateId;
    }

    public String getMarketingTemplateName() {
        return marketingTemplateName;
    }

    public void setMarketingTemplateName(String marketingTemplateName) {
        this.marketingTemplateName = marketingTemplateName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSelectedAccountCount() {
        return selectedAccountCount;
    }

    public void setSelectedAccountCount(Integer selectedAccountCount) {
        this.selectedAccountCount = selectedAccountCount;
    }

    public Integer getTargetGroupCount() {
        return targetGroupCount;
    }

    public void setTargetGroupCount(Integer targetGroupCount) {
        this.targetGroupCount = targetGroupCount;
    }

    public Integer getTargetPairCount() {
        return targetPairCount;
    }

    public void setTargetPairCount(Integer targetPairCount) {
        this.targetPairCount = targetPairCount;
    }

    public Integer getSentMessageCount() {
        return sentMessageCount;
    }

    public void setSentMessageCount(Integer sentMessageCount) {
        this.sentMessageCount = sentMessageCount;
    }

    public Integer getFailedMessageCount() {
        return failedMessageCount;
    }

    public void setFailedMessageCount(Integer failedMessageCount) {
        this.failedMessageCount = failedMessageCount;
    }

    public Integer getSendPerRound() {
        return sendPerRound;
    }

    public void setSendPerRound(Integer sendPerRound) {
        this.sendPerRound = sendPerRound;
    }

    public Integer getAccountGroupSendIntervalMs() {
        return accountGroupSendIntervalMs;
    }

    public void setAccountGroupSendIntervalMs(Integer accountGroupSendIntervalMs) {
        this.accountGroupSendIntervalMs = accountGroupSendIntervalMs;
    }

    public Integer getSendIntervalSeconds() {
        return sendIntervalSeconds;
    }

    public void setSendIntervalSeconds(Integer sendIntervalSeconds) {
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    public Boolean getOnlineCheckEnabled() {
        return onlineCheckEnabled;
    }

    public void setOnlineCheckEnabled(Boolean onlineCheckEnabled) {
        this.onlineCheckEnabled = onlineCheckEnabled;
    }

    public Boolean getAbnormalGroupSkipped() {
        return abnormalGroupSkipped;
    }

    public void setAbnormalGroupSkipped(Boolean abnormalGroupSkipped) {
        this.abnormalGroupSkipped = abnormalGroupSkipped;
    }

    public Boolean getAutoRetryEnabled() {
        return autoRetryEnabled;
    }

    public void setAutoRetryEnabled(Boolean autoRetryEnabled) {
        this.autoRetryEnabled = autoRetryEnabled;
    }

    public Integer getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(Integer retryLimit) {
        this.retryLimit = retryLimit;
    }

    public Long getCurrentRoundNo() {
        return currentRoundNo;
    }

    public void setCurrentRoundNo(Long currentRoundNo) {
        this.currentRoundNo = currentRoundNo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getAccountGroupSendAt() {
        return accountGroupSendAt;
    }

    public void setAccountGroupSendAt(Long accountGroupSendAt) {
        this.accountGroupSendAt = accountGroupSendAt;
    }

    public Long getTaskStartAt() {
        return taskStartAt;
    }

    public void setTaskStartAt(Long taskStartAt) {
        this.taskStartAt = taskStartAt;
    }

    public Long getTaskEndAt() {
        return taskEndAt;
    }

    public void setTaskEndAt(Long taskEndAt) {
        this.taskEndAt = taskEndAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getNextRoundAt() {
        return nextRoundAt;
    }

    public void setNextRoundAt(Long nextRoundAt) {
        this.nextRoundAt = nextRoundAt;
    }

    public Long getLastRoundStartedAt() {
        return lastRoundStartedAt;
    }

    public void setLastRoundStartedAt(Long lastRoundStartedAt) {
        this.lastRoundStartedAt = lastRoundStartedAt;
    }

    public Long getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
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

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
