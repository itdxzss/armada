package com.armada.task.model.vo;

/** 当前页群执行的只读运行事实，不持久化派生状态。 */
public class PullTaskExecutionObservationFact {
    /** 群执行行 ID。 */
    private Long executionId;

    /** 父任务当前业务状态。 */
    private String taskStatus;

    /** 仅当前活动波次 ID。 */
    private Long waveId;

    /** 当前波次序号。 */
    private Integer waveNo;

    /** 波次状态。 */
    private Integer waveStatus;

    /** 波次冻结调用数。 */
    private Integer plannedCallCount;

    /** 后续调用可派发时间。 */
    private Long nextDispatchAt;

    /** 当前波次最早未完成调用，优先展示已提交调用。 */
    private Long callId;

    /** 本波次内调用序号。 */
    private Integer waveCallSeq;

    /** 调用状态。 */
    private Integer callStatus;

    /** 当前调用计划料子数。 */
    private Integer plannedMaterialCount;

    /** 尚未提交调用实际绑定的待执行料子数。 */
    private Long boundMaterialCount;

    /** 用于判断是否有命令提交证据，不对外暴露。 */
    private String commandId;

    /** 当前调用提交时间。 */
    private Long submittedAt;

    /** 仍等待结果的账号动作中最早提交时间。 */
    private Long actionSubmittedAt;

    /** @return 对应数据库事实 */
    public Long getExecutionId() { return executionId; }
    /** 保存 Mapper 读取的事实。 */
    public void setExecutionId(Long value) { executionId = value; }

    /** @return 对应数据库事实 */
    public String getTaskStatus() { return taskStatus; }
    /** 保存 Mapper 读取的事实。 */
    public void setTaskStatus(String value) { taskStatus = value; }

    /** @return 对应数据库事实 */
    public Long getWaveId() { return waveId; }
    /** 保存 Mapper 读取的事实。 */
    public void setWaveId(Long value) { waveId = value; }

    /** @return 对应数据库事实 */
    public Integer getWaveNo() { return waveNo; }
    /** 保存 Mapper 读取的事实。 */
    public void setWaveNo(Integer value) { waveNo = value; }

    /** @return 对应数据库事实 */
    public Integer getWaveStatus() { return waveStatus; }
    /** 保存 Mapper 读取的事实。 */
    public void setWaveStatus(Integer value) { waveStatus = value; }

    /** @return 对应数据库事实 */
    public Integer getPlannedCallCount() { return plannedCallCount; }
    /** 保存 Mapper 读取的事实。 */
    public void setPlannedCallCount(Integer value) { plannedCallCount = value; }

    /** @return 对应数据库事实 */
    public Long getNextDispatchAt() { return nextDispatchAt; }
    /** 保存 Mapper 读取的事实。 */
    public void setNextDispatchAt(Long value) { nextDispatchAt = value; }

    /** @return 对应数据库事实 */
    public Long getCallId() { return callId; }
    /** 保存 Mapper 读取的事实。 */
    public void setCallId(Long value) { callId = value; }

    /** @return 对应数据库事实 */
    public Integer getWaveCallSeq() { return waveCallSeq; }
    /** 保存 Mapper 读取的事实。 */
    public void setWaveCallSeq(Integer value) { waveCallSeq = value; }

    /** @return 对应数据库事实 */
    public Integer getCallStatus() { return callStatus; }
    /** 保存 Mapper 读取的事实。 */
    public void setCallStatus(Integer value) { callStatus = value; }

    /** @return 对应数据库事实 */
    public Integer getPlannedMaterialCount() { return plannedMaterialCount; }
    /** 保存 Mapper 读取的事实。 */
    public void setPlannedMaterialCount(Integer value) { plannedMaterialCount = value; }

    /** @return 对应数据库事实 */
    public Long getBoundMaterialCount() { return boundMaterialCount; }
    /** 保存 Mapper 读取的事实。 */
    public void setBoundMaterialCount(Long value) { boundMaterialCount = value; }

    /** @return 对应数据库事实 */
    public String getCommandId() { return commandId; }
    /** 保存 Mapper 读取的事实。 */
    public void setCommandId(String value) { commandId = value; }

    /** @return 对应数据库事实 */
    public Long getSubmittedAt() { return submittedAt; }
    /** 保存 Mapper 读取的事实。 */
    public void setSubmittedAt(Long value) { submittedAt = value; }

    /** @return 对应数据库事实 */
    public Long getActionSubmittedAt() { return actionSubmittedAt; }
    /** 保存 Mapper 读取的事实。 */
    public void setActionSubmittedAt(Long value) { actionSubmittedAt = value; }
}
