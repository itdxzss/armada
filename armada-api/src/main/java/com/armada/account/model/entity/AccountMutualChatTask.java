package com.armada.account.model.entity;

/** 分组互聊Task的持久事实，不存凭据。 */
public class AccountMutualChatTask {
    /** 定向操作ID。 */
    private Long id;
    /** 租户ID。 */
    private Long tenantId;
    /** 发起人及数据归属。 */
    private Long createdBy;
    /** 创建幂等键。 */
    private String requestId;
    /** 左侧分组快照。 */
    private Long leftGroupId;
    /** 右侧分组快照。 */
    private Long rightGroupId;
    /** 左侧分组名称快照。 */
    private String leftGroupName;
    /** 右侧分组名称快照。 */
    private String rightGroupName;
    /** 左侧参与数。 */
    private Integer leftCount;
    /** 右侧参与数。 */
    private Integer rightCount;
    /** A 组发送的固定文本。 */
    private String leftText;
    /** B 组发送的固定文本。 */
    private String rightText;
    /** 每次完成后单账号额外等待秒数。 */
    private Integer intervalSeconds;
    /** 1待发送2已提交3发送成功4失败5待确认6已取消。 */
    private Integer status;
    /** 创建时间epoch毫秒。 */
    private Long createdAt;
    /** 更新时间epoch毫秒。 */
    private Long updatedAt;
    /** 读取定向操作ID。 */
    public Long getId() {
        return id;
    }
    /** 设置定向操作ID。 */
    public void setId(Long value) {
        id = value;
    }
    /** 读取租户ID。 */
    public Long getTenantId() {
        return tenantId;
    }
    /** 设置租户ID。 */
    public void setTenantId(Long value) {
        tenantId = value;
    }
    /** 读取发起人及数据归属。 */
    public Long getCreatedBy() {
        return createdBy;
    }
    /** 设置发起人及数据归属。 */
    public void setCreatedBy(Long value) {
        createdBy = value;
    }
    /** 读取创建幂等键。 */
    public String getRequestId() {
        return requestId;
    }
    /** 设置创建幂等键。 */
    public void setRequestId(String value) {
        requestId = value;
    }
    /** 读取左侧分组快照。 */
    public Long getLeftGroupId() {
        return leftGroupId;
    }
    /** 设置左侧分组快照。 */
    public void setLeftGroupId(Long value) {
        leftGroupId = value;
    }
    /** 读取右侧分组快照。 */
    public Long getRightGroupId() {
        return rightGroupId;
    }
    /** 设置右侧分组快照。 */
    public void setRightGroupId(Long value) {
        rightGroupId = value;
    }
    /** 读取左侧分组名称快照。 */
    public String getLeftGroupName() {
        return leftGroupName;
    }
    /** 设置左侧分组名称快照。 */
    public void setLeftGroupName(String value) {
        leftGroupName = value;
    }
    /** 读取右侧分组名称快照。 */
    public String getRightGroupName() {
        return rightGroupName;
    }
    /** 设置右侧分组名称快照。 */
    public void setRightGroupName(String value) {
        rightGroupName = value;
    }
    /** 读取左侧参与数。 */
    public Integer getLeftCount() {
        return leftCount;
    }
    /** 设置左侧参与数。 */
    public void setLeftCount(Integer value) {
        leftCount = value;
    }
    /** 读取右侧参与数。 */
    public Integer getRightCount() {
        return rightCount;
    }
    /** 设置右侧参与数。 */
    public void setRightCount(Integer value) {
        rightCount = value;
    }
    /** 读取A组发送文本快照。 */
    public String getLeftText() { return leftText; }
    /** 设置A组发送文本快照。 */
    public void setLeftText(String value) { leftText = value; }
    /** 读取B组发送文本快照。 */
    public String getRightText() { return rightText; }
    /** 设置B组发送文本快照。 */
    public void setRightText(String value) { rightText = value; }
    /** 读取每次完成后单账号额外等待秒数。 */
    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }
    /** 设置每次完成后单账号额外等待秒数。 */
    public void setIntervalSeconds(Integer value) {
        intervalSeconds = value;
    }
    /** 读取1待发送2已提交3发送成功4失败5待确认6已取消。 */
    public Integer getStatus() {
        return status;
    }
    /** 设置1待发送2已提交3发送成功4失败5待确认6已取消。 */
    public void setStatus(Integer value) {
        status = value;
    }
    /** 读取创建时间epoch毫秒。 */
    public Long getCreatedAt() {
        return createdAt;
    }
    /** 设置创建时间epoch毫秒。 */
    public void setCreatedAt(Long value) {
        createdAt = value;
    }
    /** 读取更新时间epoch毫秒。 */
    public Long getUpdatedAt() {
        return updatedAt;
    }
    /** 设置更新时间epoch毫秒。 */
    public void setUpdatedAt(Long value) {
        updatedAt = value;
    }
}
