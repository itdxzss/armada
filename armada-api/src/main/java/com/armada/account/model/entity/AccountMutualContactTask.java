package com.armada.account.model.entity;

/** 分组互存Task的持久事实，不存凭据。 */
public class AccountMutualContactTask {
    private Long id;
    private Long tenantId;
    private Long createdBy;
    private String requestId;
    private Long leftGroupId;
    private Long rightGroupId;
    private String leftGroupName;
    private String rightGroupName;
    private Integer leftCount;
    private Integer rightCount;
    private Integer intervalSeconds;
    private Integer status;
    private Long createdAt;
    private Long updatedAt;
    public Long getId() {
        return id;
    }
    public void setId(Long value) {
        id = value;
    }
    public Long getTenantId() {
        return tenantId;
    }
    public void setTenantId(Long value) {
        tenantId = value;
    }
    public Long getCreatedBy() {
        return createdBy;
    }
    public void setCreatedBy(Long value) {
        createdBy = value;
    }
    public String getRequestId() {
        return requestId;
    }
    public void setRequestId(String value) {
        requestId = value;
    }
    public Long getLeftGroupId() {
        return leftGroupId;
    }
    public void setLeftGroupId(Long value) {
        leftGroupId = value;
    }
    public Long getRightGroupId() {
        return rightGroupId;
    }
    public void setRightGroupId(Long value) {
        rightGroupId = value;
    }
    public String getLeftGroupName() {
        return leftGroupName;
    }
    public void setLeftGroupName(String value) {
        leftGroupName = value;
    }
    public String getRightGroupName() {
        return rightGroupName;
    }
    public void setRightGroupName(String value) {
        rightGroupName = value;
    }
    public Integer getLeftCount() {
        return leftCount;
    }
    public void setLeftCount(Integer value) {
        leftCount = value;
    }
    public Integer getRightCount() {
        return rightCount;
    }
    public void setRightCount(Integer value) {
        rightCount = value;
    }
    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }
    public void setIntervalSeconds(Integer value) {
        intervalSeconds = value;
    }
    public Integer getStatus() {
        return status;
    }
    public void setStatus(Integer value) {
        status = value;
    }
    public Long getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Long value) {
        createdAt = value;
    }
    public Long getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Long value) {
        updatedAt = value;
    }
}
