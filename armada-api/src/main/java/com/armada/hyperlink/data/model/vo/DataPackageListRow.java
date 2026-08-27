package com.armada.hyperlink.data.model.vo;

/** MyBatis 数据包主表与统计读模型的一对一分页投影。 */
public class DataPackageListRow {

    /** 数据包 ID。 */
    private Long id;
    /** 数据包名称。 */
    private String name;
    /** 可空备注。 */
    private String remark;
    /** 当前代次。 */
    private Integer currentGeneration;
    /** 当前代号码总数。 */
    private Integer phoneCount;
    /** 元数据乐观锁版本。 */
    private Integer version;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;
    /** 未使用号码数。 */
    private Integer unusedCount;
    /** 已领取号码数。 */
    private Integer claimedCount;
    /** 已发送号码数。 */
    private Integer sentCount;
    /** 已送达号码数。 */
    private Integer deliveredCount;
    /** 可重试失败号码数。 */
    private Integer retryableFailedCount;
    /** 未注册号码数。 */
    private Integer unregisteredCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getCurrentGeneration() { return currentGeneration; }
    public void setCurrentGeneration(Integer currentGeneration) { this.currentGeneration = currentGeneration; }
    public Integer getPhoneCount() { return phoneCount; }
    public void setPhoneCount(Integer phoneCount) { this.phoneCount = phoneCount; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Integer getUnusedCount() { return unusedCount; }
    public void setUnusedCount(Integer unusedCount) { this.unusedCount = unusedCount; }
    public Integer getClaimedCount() { return claimedCount; }
    public void setClaimedCount(Integer claimedCount) { this.claimedCount = claimedCount; }
    public Integer getSentCount() { return sentCount; }
    public void setSentCount(Integer sentCount) { this.sentCount = sentCount; }
    public Integer getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(Integer deliveredCount) { this.deliveredCount = deliveredCount; }
    public Integer getRetryableFailedCount() { return retryableFailedCount; }
    public void setRetryableFailedCount(Integer retryableFailedCount) {
        this.retryableFailedCount = retryableFailedCount;
    }
    public Integer getUnregisteredCount() { return unregisteredCount; }
    public void setUnregisteredCount(Integer unregisteredCount) { this.unregisteredCount = unregisteredCount; }
}
