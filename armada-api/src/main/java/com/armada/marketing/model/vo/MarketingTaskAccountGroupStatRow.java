package com.armada.marketing.model.vo;

/**
 * 从营销任务发送记录聚合出的账号+群组原始行。
 *
 * <p>仅用于 Mapper 承接聚合查询结果，由 Service 转换为营销任务明细视图。</p>
 */
public class MarketingTaskAccountGroupStatRow {

    /**
     * 执行发送的账号 ID。
     */
    private Long accountId;

    /**
     * 创建任务时记录的发送账号手机号。
     */
    private String accountPhone;

    /**
     * 实际发送群对应的群链接记录 ID。
     */
    private Long groupLinkId;

    /**
     * 实际发送群的 WhatsApp JID。
     */
    private String groupJid;

    /**
     * 实际发送群的邀请链接。
     */
    private String groupLinkUrl;

    /**
     * 实际发送群的最新可用名称。
     */
    private String groupName;

    /** 最新有效发送尝试状态:1=成功,2=失败;没有有效结果时为空。 */
    private Integer latestAttemptStatus;

    /** 最新有效发送尝试失败原因码。 */
    private String reasonCode;

    /** 最新有效发送尝试失败原因描述。 */
    private String reasonMessage;

    /** 最新有效发送尝试携带的原始群组检测状态。 */
    private String groupStatus;

    /** 最新有效发送尝试携带的群组检测原因。 */
    private String groupStatusReason;

    /**
     * 该账号向该群组发送成功的历史累计次数。
     */
    private Integer sentMessageCount;

    /**
     * 该账号向该群组发送失败的历史累计次数。
     */
    private Integer failedMessageCount;

    /**
     * 最近一次发送尝试或跳过的毫秒时间戳。
     */
    private Long lastAttemptAt;

    /**
     * 最近一次发送成功的毫秒时间戳。
     */
    private Long lastSentAt;

    /**
     * 最近一次发送失败或跳过的原因；最新记录成功时为空。
     */
    private String lastReason;

    /**
     * 获取执行发送的账号 ID。
     *
     * @return 执行发送的账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置执行发送的账号 ID。
     *
     * @param accountId 执行发送的账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * 获取创建任务时记录的发送账号手机号。
     *
     * @return 发送账号手机号
     */
    public String getAccountPhone() {
        return accountPhone;
    }

    /**
     * 设置创建任务时记录的发送账号手机号。
     *
     * @param accountPhone 发送账号手机号
     */
    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }

    /**
     * 获取实际发送群对应的群链接记录 ID。
     *
     * @return 群链接记录 ID
     */
    public Long getGroupLinkId() {
        return groupLinkId;
    }

    /**
     * 设置实际发送群对应的群链接记录 ID。
     *
     * @param groupLinkId 群链接记录 ID
     */
    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    /**
     * 获取实际发送群的 WhatsApp JID。
     *
     * @return 群组 JID
     */
    public String getGroupJid() {
        return groupJid;
    }

    /**
     * 设置实际发送群的 WhatsApp JID。
     *
     * @param groupJid 群组 JID
     */
    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    /**
     * 获取实际发送群的邀请链接。
     *
     * @return 群组邀请链接
     */
    public String getGroupLinkUrl() {
        return groupLinkUrl;
    }

    /**
     * 设置实际发送群的邀请链接。
     *
     * @param groupLinkUrl 群组邀请链接
     */
    public void setGroupLinkUrl(String groupLinkUrl) {
        this.groupLinkUrl = groupLinkUrl;
    }

    /**
     * 获取实际发送群的最新可用名称。
     *
     * @return 群组名称
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * 设置实际发送群的最新可用名称。
     *
     * @param groupName 群组名称
     */
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    /**
     * 获取最新有效发送尝试状态。
     *
     * @return 1=成功,2=失败;没有有效结果时为 null
     */
    public Integer getLatestAttemptStatus() {
        return latestAttemptStatus;
    }

    /**
     * 设置最新有效发送尝试状态。
     *
     * @param latestAttemptStatus 1=成功,2=失败;没有有效结果时为 null
     */
    public void setLatestAttemptStatus(Integer latestAttemptStatus) {
        this.latestAttemptStatus = latestAttemptStatus;
    }

    /**
     * 获取最新有效发送尝试失败原因码。
     *
     * @return 失败原因码
     */
    public String getReasonCode() {
        return reasonCode;
    }

    /**
     * 设置最新有效发送尝试失败原因码。
     *
     * @param reasonCode 失败原因码
     */
    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    /**
     * 获取最新有效发送尝试失败原因描述。
     *
     * @return 失败原因描述
     */
    public String getReasonMessage() {
        return reasonMessage;
    }

    /**
     * 设置最新有效发送尝试失败原因描述。
     *
     * @param reasonMessage 失败原因描述
     */
    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }

    /**
     * 获取最新有效发送尝试携带的原始群组检测状态。
     *
     * @return 原始群组检测状态
     */
    public String getGroupStatus() {
        return groupStatus;
    }

    /**
     * 设置最新有效发送尝试携带的原始群组检测状态。
     *
     * @param groupStatus 原始群组检测状态
     */
    public void setGroupStatus(String groupStatus) {
        this.groupStatus = groupStatus;
    }

    /**
     * 获取最新有效发送尝试携带的群组检测原因。
     *
     * @return 群组检测原因
     */
    public String getGroupStatusReason() {
        return groupStatusReason;
    }

    /**
     * 设置最新有效发送尝试携带的群组检测原因。
     *
     * @param groupStatusReason 群组检测原因
     */
    public void setGroupStatusReason(String groupStatusReason) {
        this.groupStatusReason = groupStatusReason;
    }

    /**
     * 获取该账号向该群组发送成功的历史累计次数。
     *
     * @return 发送成功次数
     */
    public Integer getSentMessageCount() {
        return sentMessageCount;
    }

    /**
     * 设置该账号向该群组发送成功的历史累计次数。
     *
     * @param sentMessageCount 发送成功次数
     */
    public void setSentMessageCount(Integer sentMessageCount) {
        this.sentMessageCount = sentMessageCount;
    }

    /**
     * 获取该账号向该群组发送失败的历史累计次数。
     *
     * @return 发送失败次数
     */
    public Integer getFailedMessageCount() {
        return failedMessageCount;
    }

    /**
     * 设置该账号向该群组发送失败的历史累计次数。
     *
     * @param failedMessageCount 发送失败次数
     */
    public void setFailedMessageCount(Integer failedMessageCount) {
        this.failedMessageCount = failedMessageCount;
    }

    /**
     * 获取最近一次发送尝试或跳过的毫秒时间戳。
     *
     * @return 最近尝试时间，无记录时返回 {@code null}
     */
    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    /**
     * 设置最近一次发送尝试或跳过的毫秒时间戳。
     *
     * @param lastAttemptAt 最近尝试时间，无记录时为 {@code null}
     */
    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    /**
     * 获取最近一次发送成功的毫秒时间戳。
     *
     * @return 最近发送成功时间，无成功记录时返回 {@code null}
     */
    public Long getLastSentAt() {
        return lastSentAt;
    }

    /**
     * 设置最近一次发送成功的毫秒时间戳。
     *
     * @param lastSentAt 最近发送成功时间，无成功记录时为 {@code null}
     */
    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    /**
     * 获取最近一次发送失败或跳过的原因。
     *
     * @return 失败或跳过原因，最新记录成功时返回 {@code null}
     */
    public String getLastReason() {
        return lastReason;
    }

    /**
     * 设置最近一次发送失败或跳过的原因。
     *
     * @param lastReason 失败或跳过原因，最新记录成功时为 {@code null}
     */
    public void setLastReason(String lastReason) {
        this.lastReason = lastReason;
    }
}
