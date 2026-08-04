package com.armada.task.model.entity;

/** 执行行内的账号动作（联系人、邀请、踩链接），映射 {@code pull_task_account_action}。 */
public class PullTaskAccountAction {

    /** 账号动作主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 动作类型，取值见 PullTaskAccountActionType。 */
    private Integer actionType;

    /** 动作发起方角色行 ID；踩链接入群时为目标账号自身 ID(MySQL 唯一索引中 NULL 互不相等，留空会让幂等键失效)。 */
    private Long actorGroupAccountId;

    /** 动作对象角色行 ID(→pull_task_group_account.id)。 */
    private Long targetGroupAccountId;

    /** 动作结果，取值见 PullTaskActionStatus。 */
    private Integer actionStatus;

    /** 协议命令 ID；回调按此定位。 */
    private String commandId;

    /** 失败原因码。 */
    private String reasonCode;

    /** 失败原因描述(已脱敏)。 */
    private String reasonMessage;

    /** 命令提交时间(epoch 毫秒)。 */
    private Long submittedAt;

    /** 结果回写时间(epoch 毫秒)。 */
    private Long resultAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
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

    public Long getGroupExecutionId() {
        return groupExecutionId;
    }

    public void setGroupExecutionId(Long groupExecutionId) {
        this.groupExecutionId = groupExecutionId;
    }

    public Integer getActionType() {
        return actionType;
    }

    public void setActionType(Integer actionType) {
        this.actionType = actionType;
    }

    public Long getActorGroupAccountId() {
        return actorGroupAccountId;
    }

    public void setActorGroupAccountId(Long actorGroupAccountId) {
        this.actorGroupAccountId = actorGroupAccountId;
    }

    public Long getTargetGroupAccountId() {
        return targetGroupAccountId;
    }

    public void setTargetGroupAccountId(Long targetGroupAccountId) {
        this.targetGroupAccountId = targetGroupAccountId;
    }

    public Integer getActionStatus() {
        return actionStatus;
    }

    public void setActionStatus(Integer actionStatus) {
        this.actionStatus = actionStatus;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }

    public Long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getResultAt() {
        return resultAt;
    }

    public void setResultAt(Long resultAt) {
        this.resultAt = resultAt;
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
