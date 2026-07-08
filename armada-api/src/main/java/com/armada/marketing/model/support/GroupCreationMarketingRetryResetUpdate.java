package com.armada.marketing.model.support;

/**
 * 建群营销换号后重置执行项为待处理的更新参数。
 *
 * <p>作为 Mapper 参数对象使用,重置群、营销发送和失败字段,并写入新账号快照与重试历史。</p>
 */
public class GroupCreationMarketingRetryResetUpdate {

    /** 建群营销执行项 ID。 */
    private Long id;

    /** 替换后的账号 ID。 */
    private Long accountId;

    /** 替换后的账号手机号快照。 */
    private String accountPhone;

    /** 替换后的协议层账号 ID。 */
    private String protocolAccountId;

    /** 允许重置的当前执行项状态。 */
    private Integer fromStatus;

    /** 营销发送阶段的命令 ID 乐观条件;建群阶段为空。 */
    private String expectedCommandId;

    /** 重置后的待处理状态码。 */
    private Integer pendingStatus;

    /** 下次执行时间(epoch 毫秒)。 */
    private Long nextRunAt;

    /** 追加失败记录后的重试历史 JSON。 */
    private String retryHistoryJson;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public Integer getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(Integer fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getExpectedCommandId() {
        return expectedCommandId;
    }

    public void setExpectedCommandId(String expectedCommandId) {
        this.expectedCommandId = expectedCommandId;
    }

    public Integer getPendingStatus() {
        return pendingStatus;
    }

    public void setPendingStatus(Integer pendingStatus) {
        this.pendingStatus = pendingStatus;
    }

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public String getRetryHistoryJson() {
        return retryHistoryJson;
    }

    public void setRetryHistoryJson(String retryHistoryJson) {
        this.retryHistoryJson = retryHistoryJson;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
