package com.armada.marketing.model.entity;

/** 剧本营销：ScriptMarketingSendRecord 持久化事实。 */
public class ScriptMarketingSendRecord {
    /** 结果等待截止；恢复只重置此时间，不改写原提交事实。 */
    private Long resultDeadlineAt;
    /** 读取结果等待截止。 */
    public Long getResultDeadlineAt() { return resultDeadlineAt; }
    /** 保存结果等待截止。 */
    public void setResultDeadlineAt(Long value) { resultDeadlineAt = value; }
    /** 发送记录 ID。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 任务 ID。 */
    private Long taskId;
    /** 群执行 ID。 */
    private Long groupId;
    /** 步骤下标。 */
    private Integer stepIndex;
    /** 实际发送账号。 */
    private Long accountId;
    /** 唯一原命令 ID。 */
    private String commandId;
    /** 1等待结果 2成功 3失败 4未知 5暂停未投递。 */
    private Integer status;
    /** 结果说明。 */
    private String reason;
    /** 协议消息 ID。 */
    private String messageId;
    /** 提交或恢复时间。 */
    private Long submittedAt;
    /** 结束等待时间。 */
    private Long finishedAt;
    /** 读取发送记录 ID。 */
    public Long getId() { return id; }
    /** 保存发送记录 ID。 */
    public void setId(Long value) { this.id = value; }
    /** 读取租户 ID。 */
    public Long getTenantId() { return tenantId; }
    /** 保存租户 ID。 */
    public void setTenantId(Long value) { this.tenantId = value; }
    /** 读取任务 ID。 */
    public Long getTaskId() { return taskId; }
    /** 保存任务 ID。 */
    public void setTaskId(Long value) { this.taskId = value; }
    /** 读取群执行 ID。 */
    public Long getGroupId() { return groupId; }
    /** 保存群执行 ID。 */
    public void setGroupId(Long value) { this.groupId = value; }
    /** 读取步骤下标。 */
    public Integer getStepIndex() { return stepIndex; }
    /** 保存步骤下标。 */
    public void setStepIndex(Integer value) { this.stepIndex = value; }
    /** 读取实际发送账号。 */
    public Long getAccountId() { return accountId; }
    /** 保存实际发送账号。 */
    public void setAccountId(Long value) { this.accountId = value; }
    /** 读取唯一原命令 ID。 */
    public String getCommandId() { return commandId; }
    /** 保存唯一原命令 ID。 */
    public void setCommandId(String value) { this.commandId = value; }
    /** 读取1等待结果 2成功 3失败 4未知 5暂停未投递。 */
    public Integer getStatus() { return status; }
    /** 保存1等待结果 2成功 3失败 4未知 5暂停未投递。 */
    public void setStatus(Integer value) { this.status = value; }
    /** 读取结果说明。 */
    public String getReason() { return reason; }
    /** 保存结果说明。 */
    public void setReason(String value) { this.reason = value; }
    /** 读取协议消息 ID。 */
    public String getMessageId() { return messageId; }
    /** 保存协议消息 ID。 */
    public void setMessageId(String value) { this.messageId = value; }
    /** 读取提交或恢复时间。 */
    public Long getSubmittedAt() { return submittedAt; }
    /** 保存提交或恢复时间。 */
    public void setSubmittedAt(Long value) { this.submittedAt = value; }
    /** 读取结束等待时间。 */
    public Long getFinishedAt() { return finishedAt; }
    /** 保存结束等待时间。 */
    public void setFinishedAt(Long value) { this.finishedAt = value; }
}
