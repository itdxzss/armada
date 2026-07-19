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

    /**
     * 最新已完成发送记录中的群组检测状态，未确认时为 {@code UNCONFIRMED}。
     */
    private String groupStatus;

    /**
     * 按轮次和尝试次数确定的最新发送结果：{@code SUCCESS} 或 {@code FAILED}；无有效结果时为空。
     */
    private String executionResult;

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
     * 获取最新已完成发送记录中的群组检测状态。
     *
     * @return 群组检测状态，未确认时为 {@code UNCONFIRMED}
     */
    public String getGroupStatus() {
        return groupStatus;
    }

    /**
     * 设置最新已完成发送记录中的群组检测状态。
     *
     * @param groupStatus 群组检测状态，未确认时为 {@code UNCONFIRMED}
     */
    public void setGroupStatus(String groupStatus) {
        this.groupStatus = groupStatus;
    }

    /**
     * 获取按轮次和尝试次数确定的最新发送结果。
     *
     * @return {@code SUCCESS} 或 {@code FAILED}；无有效结果时返回 {@code null}
     */
    public String getExecutionResult() {
        return executionResult;
    }

    /**
     * 设置按轮次和尝试次数确定的最新发送结果。
     *
     * @param executionResult {@code SUCCESS} 或 {@code FAILED}；无有效结果时为 {@code null}
     */
    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
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
