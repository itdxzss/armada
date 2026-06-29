package com.armada.account.model.vo;

/**
 * Mapper 投影:account_group + 聚合账号数,用于列表分页。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒(UTC)。
 */
public class AccountGroupVoRow {

    private Long id;
    private String name;
    private String remark;
    private Integer systemBuiltin;
    private long accountCount;
    private long onlineCount;
    private long riskCount;
    private long bannedCount;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getSystemBuiltin() {
        return systemBuiltin;
    }

    public void setSystemBuiltin(Integer systemBuiltin) {
        this.systemBuiltin = systemBuiltin;
    }

    public long getAccountCount() {
        return accountCount;
    }

    public void setAccountCount(long accountCount) {
        this.accountCount = accountCount;
    }

    public long getOnlineCount() {
        return onlineCount;
    }

    public void setOnlineCount(long onlineCount) {
        this.onlineCount = onlineCount;
    }

    public long getRiskCount() {
        return riskCount;
    }

    public void setRiskCount(long riskCount) {
        this.riskCount = riskCount;
    }

    public long getBannedCount() {
        return bannedCount;
    }

    public void setBannedCount(long bannedCount) {
        this.bannedCount = bannedCount;
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
}
