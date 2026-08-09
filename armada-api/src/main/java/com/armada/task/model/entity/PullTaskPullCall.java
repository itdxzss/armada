package com.armada.task.model.entity;

/** 一个拉手对同一群 JID 的一次批量加成员请求，映射 {@code pull_task_pull_call}。 */
public class PullTaskPullCall {

    /** 拉人调用主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 所属拉人波次 ID；历史完成调用可为空。 */
    private Long pullWaveId;

    /** 本执行行内调用序号。 */
    private Integer callSeq;

    /** 波次内稳定调用序号。 */
    private Integer waveCallSeq;

    /** 执行本次调用的拉手角色行 ID(→pull_task_group_account.id)。 */
    private Long pullerGroupAccountId;

    /** 执行本次调用的拉手账号 ID(→account.id)。 */
    private Long pullerAccountId;

    /** 本次调用绑定的拉手分配代际。 */
    private Long pullerAssignmentSeq;

    /** 本次计划料子人数(闭区间随机结果，不含站台)。 */
    private Integer plannedMaterialCount;

    /** 本次计划站台数。 */
    private Integer plannedStationCount;

    /** 调用状态，取值见 PullTaskPullCallStatus。 */
    private Integer callStatus;

    /** 异常批次名单核实状态，取值见 PullTaskPullCallRosterCheckStatus。 */
    private Integer rosterCheckStatus;

    /** 名单核实持久化认领时间(epoch 毫秒)。 */
    private Long rosterCheckStartedAt;

    /** 名单核实完成时间(epoch 毫秒)。 */
    private Long rosterCheckFinishedAt;

    /** 协议命令 ID；回调按此定位。 */
    private String commandId;

    /** 计划阶段生成的幂等键；崩溃恢复用原键重投。 */
    private String idempotencyKey;

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

    public Long getPullWaveId() {
        return pullWaveId;
    }

    public void setPullWaveId(Long pullWaveId) {
        this.pullWaveId = pullWaveId;
    }

    public Integer getCallSeq() {
        return callSeq;
    }

    public void setCallSeq(Integer callSeq) {
        this.callSeq = callSeq;
    }

    public Integer getWaveCallSeq() {
        return waveCallSeq;
    }

    public void setWaveCallSeq(Integer waveCallSeq) {
        this.waveCallSeq = waveCallSeq;
    }

    public Long getPullerGroupAccountId() {
        return pullerGroupAccountId;
    }

    public void setPullerGroupAccountId(Long pullerGroupAccountId) {
        this.pullerGroupAccountId = pullerGroupAccountId;
    }

    public Long getPullerAccountId() {
        return pullerAccountId;
    }

    public void setPullerAccountId(Long pullerAccountId) {
        this.pullerAccountId = pullerAccountId;
    }

    public Long getPullerAssignmentSeq() {
        return pullerAssignmentSeq;
    }

    public void setPullerAssignmentSeq(Long pullerAssignmentSeq) {
        this.pullerAssignmentSeq = pullerAssignmentSeq;
    }

    public Integer getPlannedMaterialCount() {
        return plannedMaterialCount;
    }

    public void setPlannedMaterialCount(Integer plannedMaterialCount) {
        this.plannedMaterialCount = plannedMaterialCount;
    }

    public Integer getPlannedStationCount() {
        return plannedStationCount;
    }

    public void setPlannedStationCount(Integer plannedStationCount) {
        this.plannedStationCount = plannedStationCount;
    }

    public Integer getCallStatus() {
        return callStatus;
    }

    public void setCallStatus(Integer callStatus) {
        this.callStatus = callStatus;
    }

    public Integer getRosterCheckStatus() {
        return rosterCheckStatus;
    }

    public void setRosterCheckStatus(Integer rosterCheckStatus) {
        this.rosterCheckStatus = rosterCheckStatus;
    }

    public Long getRosterCheckStartedAt() {
        return rosterCheckStartedAt;
    }

    public void setRosterCheckStartedAt(Long rosterCheckStartedAt) {
        this.rosterCheckStartedAt = rosterCheckStartedAt;
    }

    public Long getRosterCheckFinishedAt() {
        return rosterCheckFinishedAt;
    }

    public void setRosterCheckFinishedAt(Long rosterCheckFinishedAt) {
        this.rosterCheckFinishedAt = rosterCheckFinishedAt;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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
