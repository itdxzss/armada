package com.armada.marketing.model.support;

/**
 * 建群营销没有可替换账号时放弃执行项的更新参数。
 *
 * <p>作为 Mapper 参数对象使用,同时携带状态保护条件、失败原因和最终重试历史。</p>
 */
public class GroupCreationMarketingNoAvailableAccountUpdate {

    /** 建群营销执行项 ID。 */
    private Long id;

    /** 放弃原因码。 */
    private String reasonCode;

    /** 面向运营展示的放弃原因。 */
    private String reasonMessage;

    /** 允许放弃的当前执行项状态。 */
    private Integer fromStatus;

    /** 营销发送阶段的命令 ID 乐观条件;建群阶段为空。 */
    private String expectedCommandId;

    /** 追加最后一次失败后的重试历史 JSON。 */
    private String retryHistoryJson;

    /** 完成时间(epoch 毫秒),同时作为更新时间。 */
    private Long finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
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

    public String getRetryHistoryJson() {
        return retryHistoryJson;
    }

    public void setRetryHistoryJson(String retryHistoryJson) {
        this.retryHistoryJson = retryHistoryJson;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
