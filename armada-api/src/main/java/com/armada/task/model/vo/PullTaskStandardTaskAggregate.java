package com.armada.task.model.vo;

/** 普通群链接任务从执行行、逐号码结果和角色资源事实聚合出的列表读投影。 */
public class PullTaskStandardTaskAggregate {

    private Long taskId;
    private Integer totalGroupCount;
    private Integer completedGroupCount;
    private Integer failedGroupCount;
    private Integer abandonedGroupCount;
    private Integer executingGroupCount;
    private Integer waitingGroupCount;
    private Integer managerShortageGroupCount;
    private Integer pullerShortageGroupCount;
    private Integer stationShortageGroupCount;
    private Integer totalMemberCount;
    private Integer unconsumedMemberCount;
    private Integer submittedMemberCount;
    private Integer successfulMemberCount;
    private Integer failedMemberCount;
    private Integer unknownMemberCount;
    private Integer canceledMemberCount;
    private Integer availablePullerCount;
    private Long lastExecutedAt;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getTotalGroupCount() { return totalGroupCount; }
    public void setTotalGroupCount(Integer value) { totalGroupCount = value; }
    public Integer getCompletedGroupCount() { return completedGroupCount; }
    public void setCompletedGroupCount(Integer value) { completedGroupCount = value; }
    public Integer getFailedGroupCount() { return failedGroupCount; }
    public void setFailedGroupCount(Integer value) { failedGroupCount = value; }
    public Integer getAbandonedGroupCount() { return abandonedGroupCount; }
    public void setAbandonedGroupCount(Integer value) { abandonedGroupCount = value; }
    public Integer getExecutingGroupCount() { return executingGroupCount; }
    public void setExecutingGroupCount(Integer value) { executingGroupCount = value; }
    public Integer getWaitingGroupCount() { return waitingGroupCount; }
    public void setWaitingGroupCount(Integer value) { waitingGroupCount = value; }
    public Integer getManagerShortageGroupCount() { return managerShortageGroupCount; }
    public void setManagerShortageGroupCount(Integer value) { managerShortageGroupCount = value; }
    public Integer getPullerShortageGroupCount() { return pullerShortageGroupCount; }
    public void setPullerShortageGroupCount(Integer value) { pullerShortageGroupCount = value; }
    public Integer getStationShortageGroupCount() { return stationShortageGroupCount; }
    public void setStationShortageGroupCount(Integer value) { stationShortageGroupCount = value; }
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
    public Integer getAvailablePullerCount() { return availablePullerCount; }
    public void setAvailablePullerCount(Integer value) { availablePullerCount = value; }
    public Long getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Long lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }
}
