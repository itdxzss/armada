package com.armada.marketing.export.model.vo;

/** 全量导出的营销群组统计行。 */
public class MarketingTaskGroupExportRow {
    private Long joinedTaskAt;
    private Long taskId;
    private String taskName;
    private String groupName;
    private String groupLink;
    private String groupStatus;
    private String speechPermission;
    private Integer groupMemberCount;
    private Integer joinedPhoneCount;
    private Integer plannedCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer unknownCount;
    private String senderPhone;
    private String accountStatus;
    private Long firstSentAt;
    private Long lastSentAt;
    private String sendStatus;
    private String failureReason;
    private String remark;

    public Long getJoinedTaskAt() { return joinedTaskAt; }
    public void setJoinedTaskAt(Long joinedTaskAt) { this.joinedTaskAt = joinedTaskAt; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getGroupLink() { return groupLink; }
    public void setGroupLink(String groupLink) { this.groupLink = groupLink; }
    public String getGroupStatus() { return groupStatus; }
    public void setGroupStatus(String groupStatus) { this.groupStatus = groupStatus; }
    public String getSpeechPermission() { return speechPermission; }
    public void setSpeechPermission(String speechPermission) { this.speechPermission = speechPermission; }
    public Integer getGroupMemberCount() { return groupMemberCount; }
    public void setGroupMemberCount(Integer groupMemberCount) { this.groupMemberCount = groupMemberCount; }
    public Integer getJoinedPhoneCount() { return joinedPhoneCount; }
    public void setJoinedPhoneCount(Integer joinedPhoneCount) { this.joinedPhoneCount = joinedPhoneCount; }
    public Integer getPlannedCount() { return plannedCount; }
    public void setPlannedCount(Integer plannedCount) { this.plannedCount = plannedCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getUnknownCount() { return unknownCount; }
    public void setUnknownCount(Integer unknownCount) { this.unknownCount = unknownCount; }
    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }
    public String getAccountStatus() { return accountStatus; }
    public void setAccountStatus(String accountStatus) { this.accountStatus = accountStatus; }
    public Long getFirstSentAt() { return firstSentAt; }
    public void setFirstSentAt(Long firstSentAt) { this.firstSentAt = firstSentAt; }
    public Long getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(Long lastSentAt) { this.lastSentAt = lastSentAt; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
