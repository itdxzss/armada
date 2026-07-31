package com.armada.marketing.export.model.vo;

/** 全量导出的营销群组受控成员明细行。 */
public class MarketingTaskGroupMemberExportRow {

    private Long taskId;
    private String taskName;
    private String groupName;
    private String groupLink;
    private String groupStatus;
    private Integer groupMemberCount;
    private String memberPhone;
    private String role;
    private String countryName;
    private String inGroup;
    private String exitType;
    private Long joinedAt;
    private Long exitedAt;
    private String taskJoinStatus;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupLink() {
        return groupLink;
    }

    public void setGroupLink(String groupLink) {
        this.groupLink = groupLink;
    }

    public String getGroupStatus() {
        return groupStatus;
    }

    public void setGroupStatus(String groupStatus) {
        this.groupStatus = groupStatus;
    }

    public Integer getGroupMemberCount() {
        return groupMemberCount;
    }

    public void setGroupMemberCount(Integer groupMemberCount) {
        this.groupMemberCount = groupMemberCount;
    }

    public String getMemberPhone() {
        return memberPhone;
    }

    public void setMemberPhone(String memberPhone) {
        this.memberPhone = memberPhone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    public String getInGroup() {
        return inGroup;
    }

    public void setInGroup(String inGroup) {
        this.inGroup = inGroup;
    }

    public String getExitType() {
        return exitType;
    }

    public void setExitType(String exitType) {
        this.exitType = exitType;
    }

    public Long getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Long getExitedAt() {
        return exitedAt;
    }

    public void setExitedAt(Long exitedAt) {
        this.exitedAt = exitedAt;
    }

    public String getTaskJoinStatus() {
        return taskJoinStatus;
    }

    public void setTaskJoinStatus(String taskJoinStatus) {
        this.taskJoinStatus = taskJoinStatus;
    }
}
