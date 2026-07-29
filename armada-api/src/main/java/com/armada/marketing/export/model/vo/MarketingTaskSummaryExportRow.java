package com.armada.marketing.export.model.vo;

/** 全量导出的任务汇总行。 */
public class MarketingTaskSummaryExportRow {
    private Long taskCreatedAt;
    private Long taskStartedAt;
    private Long taskFinishedAt;
    private Long taskId;
    private String taskName;
    private Integer totalGroupCount;
    private Integer normalGroupCount;
    private Integer bannedGroupCount;
    private Integer dissolvedGroupCount;
    private Integer kickedGroupCount;
    private Integer noPermissionGroupCount;
    private Integer totalAccountCount;
    private Integer onlineAccountCount;
    private Integer abnormalAccountCount;
    private Integer plannedCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer unknownCount;
    private String taskStatus;

    public Long getTaskCreatedAt() { return taskCreatedAt; }
    public void setTaskCreatedAt(Long taskCreatedAt) { this.taskCreatedAt = taskCreatedAt; }
    public Long getTaskStartedAt() { return taskStartedAt; }
    public void setTaskStartedAt(Long taskStartedAt) { this.taskStartedAt = taskStartedAt; }
    public Long getTaskFinishedAt() { return taskFinishedAt; }
    public void setTaskFinishedAt(Long taskFinishedAt) { this.taskFinishedAt = taskFinishedAt; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public Integer getTotalGroupCount() { return totalGroupCount; }
    public void setTotalGroupCount(Integer totalGroupCount) { this.totalGroupCount = totalGroupCount; }
    public Integer getNormalGroupCount() { return normalGroupCount; }
    public void setNormalGroupCount(Integer normalGroupCount) { this.normalGroupCount = normalGroupCount; }
    public Integer getBannedGroupCount() { return bannedGroupCount; }
    public void setBannedGroupCount(Integer bannedGroupCount) { this.bannedGroupCount = bannedGroupCount; }
    public Integer getDissolvedGroupCount() { return dissolvedGroupCount; }
    public void setDissolvedGroupCount(Integer dissolvedGroupCount) { this.dissolvedGroupCount = dissolvedGroupCount; }
    public Integer getKickedGroupCount() { return kickedGroupCount; }
    public void setKickedGroupCount(Integer kickedGroupCount) { this.kickedGroupCount = kickedGroupCount; }
    public Integer getNoPermissionGroupCount() { return noPermissionGroupCount; }
    public void setNoPermissionGroupCount(Integer noPermissionGroupCount) { this.noPermissionGroupCount = noPermissionGroupCount; }
    public Integer getTotalAccountCount() { return totalAccountCount; }
    public void setTotalAccountCount(Integer totalAccountCount) { this.totalAccountCount = totalAccountCount; }
    public Integer getOnlineAccountCount() { return onlineAccountCount; }
    public void setOnlineAccountCount(Integer onlineAccountCount) { this.onlineAccountCount = onlineAccountCount; }
    public Integer getAbnormalAccountCount() { return abnormalAccountCount; }
    public void setAbnormalAccountCount(Integer abnormalAccountCount) { this.abnormalAccountCount = abnormalAccountCount; }
    public Integer getPlannedCount() { return plannedCount; }
    public void setPlannedCount(Integer plannedCount) { this.plannedCount = plannedCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getUnknownCount() { return unknownCount; }
    public void setUnknownCount(Integer unknownCount) { this.unknownCount = unknownCount; }
    public String getTaskStatus() { return taskStatus; }
    public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }
}
