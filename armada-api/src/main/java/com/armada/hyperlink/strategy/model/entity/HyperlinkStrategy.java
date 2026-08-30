package com.armada.hyperlink.strategy.model.entity;

/** 超链发送策略唯一事实；模板与任务快照均映射 {@code hyperlink_strategy}。 */
public class HyperlinkStrategy {

    /** 策略主键。 */
    private Long id;
    /** 租户主键。 */
    private Long tenantId;
    /** 1=模板，2=任务快照。 */
    private Integer strategyScope;
    /** 任务快照所属任务。 */
    private Long ownerTaskId;
    /** 任务快照来源模板，仅追溯。 */
    private Long sourceStrategyId;
    /** 模板名称；任务快照为空。 */
    private String strategyName;
    /** 任务模式数据库码。 */
    private Integer taskType;
    /** 账号筛选 JSON 快照。 */
    private String accountFilter;
    /** 最大执行账号数。 */
    private Integer concurrentNum;
    /** 最大使用账号数。 */
    private Integer maxUseAccount;
    /** 单账号最大发送数。 */
    private Integer accountMaxSendNum;
    /** 周期间隔分钟。 */
    private Integer taskIntervalMinutes;
    /** 是否启用。 */
    private Boolean enabled;
    /** 乐观锁版本。 */
    private Integer version;
    /** 创建人。 */
    private Long createdBy;
    /** 创建时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    /** 软删除时间。 */
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Integer getStrategyScope() { return strategyScope; }
    public void setStrategyScope(Integer value) { this.strategyScope = value; }
    public Long getOwnerTaskId() { return ownerTaskId; }
    public void setOwnerTaskId(Long value) { this.ownerTaskId = value; }
    public Long getSourceStrategyId() { return sourceStrategyId; }
    public void setSourceStrategyId(Long value) { this.sourceStrategyId = value; }
    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer taskType) { this.taskType = taskType; }
    public String getAccountFilter() { return accountFilter; }
    public void setAccountFilter(String accountFilter) { this.accountFilter = accountFilter; }
    public Integer getConcurrentNum() { return concurrentNum; }
    public void setConcurrentNum(Integer concurrentNum) { this.concurrentNum = concurrentNum; }
    public Integer getMaxUseAccount() { return maxUseAccount; }
    public void setMaxUseAccount(Integer maxUseAccount) { this.maxUseAccount = maxUseAccount; }
    public Integer getAccountMaxSendNum() { return accountMaxSendNum; }
    public void setAccountMaxSendNum(Integer accountMaxSendNum) {
        this.accountMaxSendNum = accountMaxSendNum;
    }
    public Integer getTaskIntervalMinutes() { return taskIntervalMinutes; }
    public void setTaskIntervalMinutes(Integer taskIntervalMinutes) {
        this.taskIntervalMinutes = taskIntervalMinutes;
    }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
