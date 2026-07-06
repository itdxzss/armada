package com.armada.marketing.model.vo;

/**
 * 从发送记录聚合出的账号+群组原始行。
 */
public class MarketingTaskAccountGroupStatRow {

    private Long accountId;
    private String accountPhone;
    private Long groupLinkId;
    private String groupJid;
    private String groupLinkUrl;
    private String groupName;
    private Integer sentMessageCount;
    private Integer failedMessageCount;
    private Long lastAttemptAt;
    private Long lastSentAt;
    private String lastReason;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountPhone() {
        return accountPhone;
    }

    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getGroupLinkUrl() {
        return groupLinkUrl;
    }

    public void setGroupLinkUrl(String groupLinkUrl) {
        this.groupLinkUrl = groupLinkUrl;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Integer getSentMessageCount() {
        return sentMessageCount;
    }

    public void setSentMessageCount(Integer sentMessageCount) {
        this.sentMessageCount = sentMessageCount;
    }

    public Integer getFailedMessageCount() {
        return failedMessageCount;
    }

    public void setFailedMessageCount(Integer failedMessageCount) {
        this.failedMessageCount = failedMessageCount;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Long getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public String getLastReason() {
        return lastReason;
    }

    public void setLastReason(String lastReason) {
        this.lastReason = lastReason;
    }
}
