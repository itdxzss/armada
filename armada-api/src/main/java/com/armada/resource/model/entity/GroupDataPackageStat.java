package com.armada.resource.model.entity;

/** 拉群数据包Stat数据库/查询数据。 */
public class GroupDataPackageStat {
    /** 数据包ID。 */
    private Long packageId;
    /** 号码代次。 */
    private Integer generation;
    /** 主要国家。 */
    private String primaryCountryIso2;
    /** 主要国家大洲。 */
    private String continent;
    /** 总数。 */
    private Long totalCount;
    /** 未用数。 */
    private Long unusedCount;
    /** 占用数。 */
    private Long claimedCount;
    /** 成功数。 */
    private Long successCount;
    /** 失败总数含隐私拒绝和未注册。 */
    private Long failedCount;
    /** 隐私拒绝数。 */
    private Long privacyRejectedCount;
    /** 未注册数。 */
    private Long unregisteredCount;
    /** 待确认数。 */
    private Long unknownCount;
    /** 读取数据包ID。 */
    public Long getPackageId() { return packageId; }
    /** 设置数据包ID。 */
    public void setPackageId(Long value) { packageId = value; }
    /** 读取号码代次。 */
    public Integer getGeneration() { return generation; }
    /** 设置号码代次。 */
    public void setGeneration(Integer value) { generation = value; }
    /** 读取主要国家。 */
    public String getPrimaryCountryIso2() { return primaryCountryIso2; }
    /** 设置主要国家。 */
    public void setPrimaryCountryIso2(String value) { primaryCountryIso2 = value; }
    /** 读取主要国家大洲。 */
    public String getContinent() { return continent; }
    /** 设置主要国家大洲。 */
    public void setContinent(String value) { continent = value; }
    /** 读取总数。 */
    public Long getTotalCount() { return totalCount; }
    /** 设置总数。 */
    public void setTotalCount(Long value) { totalCount = value; }
    /** 读取未用数。 */
    public Long getUnusedCount() { return unusedCount; }
    /** 设置未用数。 */
    public void setUnusedCount(Long value) { unusedCount = value; }
    /** 读取占用数。 */
    public Long getClaimedCount() { return claimedCount; }
    /** 设置占用数。 */
    public void setClaimedCount(Long value) { claimedCount = value; }
    /** 读取成功数。 */
    public Long getSuccessCount() { return successCount; }
    /** 设置成功数。 */
    public void setSuccessCount(Long value) { successCount = value; }
    /** 读取失败总数含隐私拒绝和未注册。 */
    public Long getFailedCount() { return failedCount; }
    /** 设置失败总数含隐私拒绝和未注册。 */
    public void setFailedCount(Long value) { failedCount = value; }
    /** 读取隐私拒绝数。 */
    public Long getPrivacyRejectedCount() { return privacyRejectedCount; }
    /** 设置隐私拒绝数。 */
    public void setPrivacyRejectedCount(Long value) { privacyRejectedCount = value; }
    /** 读取未注册数。 */
    public Long getUnregisteredCount() { return unregisteredCount; }
    /** 设置未注册数。 */
    public void setUnregisteredCount(Long value) { unregisteredCount = value; }
    /** 读取待确认数。 */
    public Long getUnknownCount() { return unknownCount; }
    /** 设置待确认数。 */
    public void setUnknownCount(Long value) { unknownCount = value; }
}
