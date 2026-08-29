package com.armada.contact.task.model.entity;

import java.math.BigDecimal;

/** 通讯录营销任务主表行，对应 contact_friend_task 表。 */
public class ContactFriendTask {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 任务名称，仅后台展示。 */
    private String name;
    /** 消息类型：0 链接消息 / 1 图文消息，创建后不可改。 */
    private Integer messageType;
    /** 消息标题，仅链接消息。 */
    private String title;
    /** 链接描述，仅链接消息。 */
    private String description;
    /** 推广链接，仅链接消息。 */
    private String promotionLink;
    /** 正文内容或图文文案。 */
    private String content;
    /** 预览图或配图，引用 marketing_template_file.id。 */
    private Long previewImageFileId;
    /** 账号筛选条件 JSON，白名单归一化后落库。 */
    private String accountFilter;
    /** 单号发送最小间隔秒，带一位小数。 */
    private BigDecimal msgIntervalMinSec;
    /** 单号发送最大间隔秒，带一位小数。 */
    private BigDecimal msgIntervalMaxSec;
    /** 最大执行账号数。 */
    private Integer concurrency;
    /** 每号最大发送数，0 表示全部联系人。 */
    private Integer maxSendsPerAccount;
    /** 单条消息失败最大重试次数。 */
    private Integer retryMax;
    /** 启动方式：now 立即 / scheduled 延后。 */
    private String startMode;
    /** 延后执行分钟数。 */
    private Integer taskDelayMinutes;
    /** 计划开始时间（epoch 毫秒）。 */
    private Long taskStartAt;
    /** 任务开关：0 已停用仅保存 / 1 启用。 */
    private Integer isEnabled;
    /** 运行状态：0 未开始 1 进行中 2 已完成 3 已暂停 4 已停止。 */
    private Integer runStatus;
    /** 下一轮调度时间（epoch 毫秒）。 */
    private Long nextRoundAt;
    /** 计划发送总条数。 */
    private Integer totalSendNum;
    /** 成功送达条数。 */
    private Integer successMessageNum;
    /** 实际参与发送的账号数。 */
    private Integer usedAccountCount;
    /** 发送期间被封禁的账号数。 */
    private Integer invalidAccountNum;
    /** 号均发量。 */
    private BigDecimal avgSendPerAccount;
    /** 创建人 user_id。 */
    private Long createdBy;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;
    /** 软删时间（epoch 毫秒），NULL 为未删。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMessageType() {
        return messageType;
    }

    public void setMessageType(Integer messageType) {
        this.messageType = messageType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPromotionLink() {
        return promotionLink;
    }

    public void setPromotionLink(String promotionLink) {
        this.promotionLink = promotionLink;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getPreviewImageFileId() {
        return previewImageFileId;
    }

    public void setPreviewImageFileId(Long previewImageFileId) {
        this.previewImageFileId = previewImageFileId;
    }

    public String getAccountFilter() {
        return accountFilter;
    }

    public void setAccountFilter(String accountFilter) {
        this.accountFilter = accountFilter;
    }

    public BigDecimal getMsgIntervalMinSec() {
        return msgIntervalMinSec;
    }

    public void setMsgIntervalMinSec(BigDecimal msgIntervalMinSec) {
        this.msgIntervalMinSec = msgIntervalMinSec;
    }

    public BigDecimal getMsgIntervalMaxSec() {
        return msgIntervalMaxSec;
    }

    public void setMsgIntervalMaxSec(BigDecimal msgIntervalMaxSec) {
        this.msgIntervalMaxSec = msgIntervalMaxSec;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
    }

    public Integer getMaxSendsPerAccount() {
        return maxSendsPerAccount;
    }

    public void setMaxSendsPerAccount(Integer maxSendsPerAccount) {
        this.maxSendsPerAccount = maxSendsPerAccount;
    }

    public Integer getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Integer retryMax) {
        this.retryMax = retryMax;
    }

    public String getStartMode() {
        return startMode;
    }

    public void setStartMode(String startMode) {
        this.startMode = startMode;
    }

    public Integer getTaskDelayMinutes() {
        return taskDelayMinutes;
    }

    public void setTaskDelayMinutes(Integer taskDelayMinutes) {
        this.taskDelayMinutes = taskDelayMinutes;
    }

    public Long getTaskStartAt() {
        return taskStartAt;
    }

    public void setTaskStartAt(Long taskStartAt) {
        this.taskStartAt = taskStartAt;
    }

    public Integer getIsEnabled() {
        return isEnabled;
    }

    public void setIsEnabled(Integer isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Integer getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(Integer runStatus) {
        this.runStatus = runStatus;
    }

    public Long getNextRoundAt() {
        return nextRoundAt;
    }

    public void setNextRoundAt(Long nextRoundAt) {
        this.nextRoundAt = nextRoundAt;
    }

    public Integer getTotalSendNum() {
        return totalSendNum;
    }

    public void setTotalSendNum(Integer totalSendNum) {
        this.totalSendNum = totalSendNum;
    }

    public Integer getSuccessMessageNum() {
        return successMessageNum;
    }

    public void setSuccessMessageNum(Integer successMessageNum) {
        this.successMessageNum = successMessageNum;
    }

    public Integer getUsedAccountCount() {
        return usedAccountCount;
    }

    public void setUsedAccountCount(Integer usedAccountCount) {
        this.usedAccountCount = usedAccountCount;
    }

    public Integer getInvalidAccountNum() {
        return invalidAccountNum;
    }

    public void setInvalidAccountNum(Integer invalidAccountNum) {
        this.invalidAccountNum = invalidAccountNum;
    }

    public BigDecimal getAvgSendPerAccount() {
        return avgSendPerAccount;
    }

    public void setAvgSendPerAccount(BigDecimal avgSendPerAccount) {
        this.avgSendPerAccount = avgSendPerAccount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
