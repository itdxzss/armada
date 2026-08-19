package com.armada.task.model.entity;

/** 普通群链接任务冻结执行配置，映射 {@code pull_task_standard_setting}。 */
public class PullTaskStandardSetting {

    private Long tenantId;
    private Long taskId;
    private Integer autoStart;
    private Long sourceGroupFolderId;
    private String sourceGroupFolderName;
    private Integer materialAdminTiming;
    private Integer pullerSyncMode;
    private Integer clearExistingMembers;
    private Integer pullerJoinByLink;
    private Integer earlyPullCount;
    private Integer earlyPullCallCount;
    private Integer pullCountMin;
    private Integer pullCountMax;
    private Integer pullIntervalSeconds;
    private Integer pullerCountPerGroup;
    private Integer stationCountPerCall;
    /** 建群时作为初始成员加入的站台数量；默认 0 对齐列的 NOT NULL DEFAULT 0。 */
    private Integer initialStationCount = 0;
    private Integer concurrentGroupCount;
    private Integer pullerRiskMinutes;
    private Integer requiredManagerCount;
    private Long managerGroupId;
    private Long pullerGroupId;
    private Long stationGroupId;
    private Long creatorGroupId;
    private Long managerFinishGroupId;
    private Long pullerFinishGroupId;
    private String managerGroupName;
    private String pullerGroupName;
    private String stationGroupName;
    private String creatorGroupName;
    private String managerFinishGroupName;
    private String pullerFinishGroupName;
    private Long createdAt;
    private Long updatedAt;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAutoStart() { return autoStart; }
    public void setAutoStart(Integer autoStart) { this.autoStart = autoStart; }
    public Long getSourceGroupFolderId() { return sourceGroupFolderId; }
    public void setSourceGroupFolderId(Long value) { this.sourceGroupFolderId = value; }
    public String getSourceGroupFolderName() { return sourceGroupFolderName; }
    public void setSourceGroupFolderName(String value) { this.sourceGroupFolderName = value; }
    public Integer getMaterialAdminTiming() { return materialAdminTiming; }
    public void setMaterialAdminTiming(Integer value) { this.materialAdminTiming = value; }
    public Integer getPullerSyncMode() { return pullerSyncMode; }
    public void setPullerSyncMode(Integer pullerSyncMode) { this.pullerSyncMode = pullerSyncMode; }
    public Integer getClearExistingMembers() { return clearExistingMembers; }
    public void setClearExistingMembers(Integer value) { this.clearExistingMembers = value; }
    public Integer getPullerJoinByLink() { return pullerJoinByLink; }
    public void setPullerJoinByLink(Integer value) { this.pullerJoinByLink = value; }
    public Integer getEarlyPullCount() { return earlyPullCount; }
    public void setEarlyPullCount(Integer value) { this.earlyPullCount = value; }
    public Integer getEarlyPullCallCount() { return earlyPullCallCount; }
    public void setEarlyPullCallCount(Integer value) { this.earlyPullCallCount = value; }
    public Integer getPullCountMin() { return pullCountMin; }
    public void setPullCountMin(Integer pullCountMin) { this.pullCountMin = pullCountMin; }
    public Integer getPullCountMax() { return pullCountMax; }
    public void setPullCountMax(Integer pullCountMax) { this.pullCountMax = pullCountMax; }
    public Integer getPullIntervalSeconds() { return pullIntervalSeconds; }
    public void setPullIntervalSeconds(Integer value) { this.pullIntervalSeconds = value; }
    public Integer getPullerCountPerGroup() { return pullerCountPerGroup; }
    public void setPullerCountPerGroup(Integer value) { this.pullerCountPerGroup = value; }
    public Integer getStationCountPerCall() { return stationCountPerCall; }
    public void setStationCountPerCall(Integer value) { this.stationCountPerCall = value; }
    public Integer getInitialStationCount() { return initialStationCount; }
    public void setInitialStationCount(Integer value) { this.initialStationCount = value; }
    public Integer getConcurrentGroupCount() { return concurrentGroupCount; }
    public void setConcurrentGroupCount(Integer value) { this.concurrentGroupCount = value; }
    public Integer getPullerRiskMinutes() { return pullerRiskMinutes; }
    public void setPullerRiskMinutes(Integer value) { this.pullerRiskMinutes = value; }
    public Integer getRequiredManagerCount() { return requiredManagerCount; }
    public void setRequiredManagerCount(Integer value) { this.requiredManagerCount = value; }
    public Long getManagerGroupId() { return managerGroupId; }
    public void setManagerGroupId(Long managerGroupId) { this.managerGroupId = managerGroupId; }
    public Long getPullerGroupId() { return pullerGroupId; }
    public void setPullerGroupId(Long pullerGroupId) { this.pullerGroupId = pullerGroupId; }
    public Long getStationGroupId() { return stationGroupId; }
    public void setStationGroupId(Long stationGroupId) { this.stationGroupId = stationGroupId; }
    public Long getCreatorGroupId() { return creatorGroupId; }
    public void setCreatorGroupId(Long creatorGroupId) { this.creatorGroupId = creatorGroupId; }
    public Long getManagerFinishGroupId() { return managerFinishGroupId; }
    public void setManagerFinishGroupId(Long value) { this.managerFinishGroupId = value; }
    public Long getPullerFinishGroupId() { return pullerFinishGroupId; }
    public void setPullerFinishGroupId(Long value) { this.pullerFinishGroupId = value; }
    public String getManagerGroupName() { return managerGroupName; }
    public void setManagerGroupName(String value) { this.managerGroupName = value; }
    public String getPullerGroupName() { return pullerGroupName; }
    public void setPullerGroupName(String value) { this.pullerGroupName = value; }
    public String getStationGroupName() { return stationGroupName; }
    public void setStationGroupName(String value) { this.stationGroupName = value; }
    public String getCreatorGroupName() { return creatorGroupName; }
    public void setCreatorGroupName(String value) { this.creatorGroupName = value; }
    public String getManagerFinishGroupName() { return managerFinishGroupName; }
    public void setManagerFinishGroupName(String value) { this.managerFinishGroupName = value; }
    public String getPullerFinishGroupName() { return pullerFinishGroupName; }
    public void setPullerFinishGroupName(String value) { this.pullerFinishGroupName = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
