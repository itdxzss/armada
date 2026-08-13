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

    /** 分组持久化营销占用类型；为空表示空闲。 */
    private Integer marketingOccupancyType;

    /** 当前占用营销任务 ID；为空表示空闲。 */
    private Long marketingOccupancyTaskId;

    /** 营销分组锁定时间(epoch 毫秒)。 */
    private Long marketingLockedAt;
    private Integer systemBuiltin;
    private long accountCount;
    private long onlineCount;
    private long executableOnlineCount;
    private long riskCount;
    private long restrictedCount;
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

    public Integer getMarketingOccupancyType() {
        return marketingOccupancyType;
    }

    public void setMarketingOccupancyType(Integer marketingOccupancyType) {
        this.marketingOccupancyType = marketingOccupancyType;
    }

    public Long getMarketingOccupancyTaskId() {
        return marketingOccupancyTaskId;
    }

    public void setMarketingOccupancyTaskId(Long marketingOccupancyTaskId) {
        this.marketingOccupancyTaskId = marketingOccupancyTaskId;
    }

    public Long getMarketingLockedAt() {
        return marketingLockedAt;
    }

    public void setMarketingLockedAt(Long marketingLockedAt) {
        this.marketingLockedAt = marketingLockedAt;
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

    public long getExecutableOnlineCount() {
        return executableOnlineCount;
    }

    public void setExecutableOnlineCount(long executableOnlineCount) {
        this.executableOnlineCount = executableOnlineCount;
    }

    public long getRiskCount() {
        return riskCount;
    }

    public void setRiskCount(long riskCount) {
        this.riskCount = riskCount;
    }

    public long getRestrictedCount() {
        return restrictedCount;
    }

    public void setRestrictedCount(long restrictedCount) {
        this.restrictedCount = restrictedCount;
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
