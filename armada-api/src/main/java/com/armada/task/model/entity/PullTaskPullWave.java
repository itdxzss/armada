package com.armada.task.model.entity;

/** 普通群链接拉人的完整计划与统一结算波次，映射 {@code pull_task_pull_wave}。 */
public class PullTaskPullWave {

    /** 波次主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID。 */
    private Long taskId;

    /** 所属执行行 ID。 */
    private Long groupExecutionId;

    /** 执行行内单调递增波次号。 */
    private Integer waveNo;

    /** 波次类型，取值见 PullTaskPullWaveType。 */
    private Integer waveType;

    /** 波次状态，取值见 PullTaskPullWaveStatus。 */
    private Integer waveStatus;

    /** 波次冻结调用数。 */
    private Integer plannedCallCount;

    /** 下一待派发波次内调用序号。 */
    private Integer nextCallSeq;

    /** 下一调用可派发时间(epoch 毫秒)。 */
    private Long nextDispatchAt;

    /** 全部调用派发完成时间(epoch 毫秒)。 */
    private Long dispatchCompletedAt;

    /** 全部参与者结果结算完成时间(epoch 毫秒)。 */
    private Long settledAt;

    /** 波次状态更新乐观锁版本号。 */
    private Integer version;

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

    public Integer getWaveNo() {
        return waveNo;
    }

    public void setWaveNo(Integer waveNo) {
        this.waveNo = waveNo;
    }

    public Integer getWaveType() {
        return waveType;
    }

    public void setWaveType(Integer waveType) {
        this.waveType = waveType;
    }

    public Integer getWaveStatus() {
        return waveStatus;
    }

    public void setWaveStatus(Integer waveStatus) {
        this.waveStatus = waveStatus;
    }

    public Integer getPlannedCallCount() {
        return plannedCallCount;
    }

    public void setPlannedCallCount(Integer plannedCallCount) {
        this.plannedCallCount = plannedCallCount;
    }

    public Integer getNextCallSeq() {
        return nextCallSeq;
    }

    public void setNextCallSeq(Integer nextCallSeq) {
        this.nextCallSeq = nextCallSeq;
    }

    public Long getNextDispatchAt() {
        return nextDispatchAt;
    }

    public void setNextDispatchAt(Long nextDispatchAt) {
        this.nextDispatchAt = nextDispatchAt;
    }

    public Long getDispatchCompletedAt() {
        return dispatchCompletedAt;
    }

    public void setDispatchCompletedAt(Long dispatchCompletedAt) {
        this.dispatchCompletedAt = dispatchCompletedAt;
    }

    public Long getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Long settledAt) {
        this.settledAt = settledAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
