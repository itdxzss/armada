package com.armada.marketing.model.entity;

/** 剧本营销：ScriptMarketingTask 持久化事实。 */
public class ScriptMarketingTask {
    /** 任务 ID。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 创建用户。 */
    private Long createdBy;
    /** 任务名称。 */
    private String taskName;
    /** 有序步骤配置。 */
    private String stepsJson;
    /** 0草稿 1运行 2暂停 3完成 4关闭。 */
    private Integer status;
    /** 统一发送间隔秒。 */
    private Integer intervalSeconds;
    /** 计划开始时间。 */
    private Long startAt;
    /** 可选截止时间。 */
    private Long endAt;
    /** 创建时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    /** 推手账号分组；空值仅用于存量固定账号任务。 */
    private Long accountGroupId;
    /** 自动暂停原因，用户继续后清除。 */
    private String pauseReason;
    /** 读取推手分组。 */
    public Long getAccountGroupId() { return accountGroupId; }
    /** 设置推手分组。 */
    public void setAccountGroupId(Long value) { accountGroupId = value; }
    /** 读取暂停原因。 */
    public String getPauseReason() { return pauseReason; }
    /** 设置暂停原因。 */
    public void setPauseReason(String value) { pauseReason = value; }
    /** 读取任务 ID。 */
    public Long getId() { return id; }
    /** 保存任务 ID。 */
    public void setId(Long value) { this.id = value; }
    /** 读取租户 ID。 */
    public Long getTenantId() { return tenantId; }
    /** 保存租户 ID。 */
    public void setTenantId(Long value) { this.tenantId = value; }
    /** 读取创建用户。 */
    public Long getCreatedBy() { return createdBy; }
    /** 保存创建用户。 */
    public void setCreatedBy(Long value) { this.createdBy = value; }
    /** 读取任务名称。 */
    public String getTaskName() { return taskName; }
    /** 保存任务名称。 */
    public void setTaskName(String value) { this.taskName = value; }
    /** 读取有序步骤配置。 */
    public String getStepsJson() { return stepsJson; }
    /** 保存有序步骤配置。 */
    public void setStepsJson(String value) { this.stepsJson = value; }
    /** 读取0草稿 1运行 2暂停 3完成 4关闭。 */
    public Integer getStatus() { return status; }
    /** 保存0草稿 1运行 2暂停 3完成 4关闭。 */
    public void setStatus(Integer value) { this.status = value; }
    /** 读取统一发送间隔秒。 */
    public Integer getIntervalSeconds() { return intervalSeconds; }
    /** 保存统一发送间隔秒。 */
    public void setIntervalSeconds(Integer value) { this.intervalSeconds = value; }
    /** 读取计划开始时间。 */
    public Long getStartAt() { return startAt; }
    /** 保存计划开始时间。 */
    public void setStartAt(Long value) { this.startAt = value; }
    /** 读取可选截止时间。 */
    public Long getEndAt() { return endAt; }
    /** 保存可选截止时间。 */
    public void setEndAt(Long value) { this.endAt = value; }
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 保存创建时间。 */
    public void setCreatedAt(Long value) { this.createdAt = value; }
    /** 读取更新时间。 */
    public Long getUpdatedAt() { return updatedAt; }
    /** 保存更新时间。 */
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
