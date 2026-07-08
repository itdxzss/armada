package com.armada.marketing.model.support;

/**
 * 建群营销执行项领取后换号重试的账号快照更新参数。
 *
 * <p>作为 Mapper 参数对象使用,收拢账号替换和重试历史字段,避免 Mapper 方法暴露过多散参。</p>
 */
public class GroupCreationMarketingClaimRetryAccountUpdate {

    /** 建群营销执行项 ID。 */
    private Long id;

    /** 替换后的账号 ID。 */
    private Long accountId;

    /** 替换后的账号手机号快照。 */
    private String accountPhone;

    /** 替换后的协议层账号 ID。 */
    private String protocolAccountId;

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
