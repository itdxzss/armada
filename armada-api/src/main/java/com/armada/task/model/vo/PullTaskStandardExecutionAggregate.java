package com.armada.task.model.vo;

/** 单执行行的料子结果与管理员、拉手、站台当前数/计划数投影。 */
public class PullTaskStandardExecutionAggregate {

    private Long executionId;
    private Integer totalMemberCount;
    private Integer unconsumedMemberCount;
    private Integer submittedMemberCount;
    private Integer successfulMemberCount;
    private Integer failedMemberCount;
    private Integer unknownMemberCount;
    private Integer canceledMemberCount;
    private Integer requiredManagerCount;
    private Integer plannedPullerCount;
    private Integer plannedStationCount;
    private Integer currentManagerCount;
    private Integer currentPullerCount;
    private Integer currentStationCount;

    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }
    public Integer getTotalMemberCount() { return totalMemberCount; }
    public void setTotalMemberCount(Integer value) { totalMemberCount = value; }
    public Integer getUnconsumedMemberCount() { return unconsumedMemberCount; }
    public void setUnconsumedMemberCount(Integer value) { unconsumedMemberCount = value; }
    public Integer getSubmittedMemberCount() { return submittedMemberCount; }
    public void setSubmittedMemberCount(Integer value) { submittedMemberCount = value; }
    public Integer getSuccessfulMemberCount() { return successfulMemberCount; }
    public void setSuccessfulMemberCount(Integer value) { successfulMemberCount = value; }
    public Integer getFailedMemberCount() { return failedMemberCount; }
    public void setFailedMemberCount(Integer value) { failedMemberCount = value; }
    public Integer getUnknownMemberCount() { return unknownMemberCount; }
    public void setUnknownMemberCount(Integer value) { unknownMemberCount = value; }
    public Integer getCanceledMemberCount() { return canceledMemberCount; }
    public void setCanceledMemberCount(Integer value) { canceledMemberCount = value; }
    public Integer getRequiredManagerCount() { return requiredManagerCount; }
    public void setRequiredManagerCount(Integer value) { requiredManagerCount = value; }
    public Integer getPlannedPullerCount() { return plannedPullerCount; }
    public void setPlannedPullerCount(Integer value) { plannedPullerCount = value; }
    public Integer getPlannedStationCount() { return plannedStationCount; }
    public void setPlannedStationCount(Integer value) { plannedStationCount = value; }
    public Integer getCurrentManagerCount() { return currentManagerCount; }
    public void setCurrentManagerCount(Integer value) { currentManagerCount = value; }
    public Integer getCurrentPullerCount() { return currentPullerCount; }
    public void setCurrentPullerCount(Integer value) { currentPullerCount = value; }
    public Integer getCurrentStationCount() { return currentStationCount; }
    public void setCurrentStationCount(Integer value) { currentStationCount = value; }
}
