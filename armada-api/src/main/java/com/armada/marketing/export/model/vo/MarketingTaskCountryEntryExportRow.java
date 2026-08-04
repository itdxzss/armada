package com.armada.marketing.export.model.vo;

/** 按国家导出的成功进群明细行。 */
public class MarketingTaskCountryEntryExportRow {
    private Long joinedAt;
    private Long taskId;
    private String taskName;
    private String countryName;
    private String countryIso2;
    private String countryPhonePrefix;
    private String actualPhone;
    private String groupName;
    private String groupLink;
    private String groupStatus;
    private String speechPermission;
    private String senderPhone;
    private Integer joinedPhoneCount;
    private Integer marketingCount;

    public Long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Long joinedAt) { this.joinedAt = joinedAt; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getCountryName() { return countryName; }
    public void setCountryName(String countryName) { this.countryName = countryName; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String countryIso2) { this.countryIso2 = countryIso2; }
    public String getCountryPhonePrefix() { return countryPhonePrefix; }
    public void setCountryPhonePrefix(String countryPhonePrefix) { this.countryPhonePrefix = countryPhonePrefix; }
    public String getActualPhone() { return actualPhone; }
    public void setActualPhone(String actualPhone) { this.actualPhone = actualPhone; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getGroupLink() { return groupLink; }
    public void setGroupLink(String groupLink) { this.groupLink = groupLink; }
    public String getGroupStatus() { return groupStatus; }
    public void setGroupStatus(String groupStatus) { this.groupStatus = groupStatus; }
    public String getSpeechPermission() { return speechPermission; }
    public void setSpeechPermission(String speechPermission) { this.speechPermission = speechPermission; }
    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }
    public Integer getJoinedPhoneCount() { return joinedPhoneCount; }
    public void setJoinedPhoneCount(Integer joinedPhoneCount) { this.joinedPhoneCount = joinedPhoneCount; }
    public Integer getMarketingCount() { return marketingCount; }
    public void setMarketingCount(Integer marketingCount) { this.marketingCount = marketingCount; }
}
