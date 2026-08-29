package com.armada.hyperlink.data.model.entity;

/** 数据包当前代号码池统计读模型。 */
public class DataPackageStat {

    /** 数据包主键，同时是本表主键。 */
    private Long dataPackageId;
    /** 租户 ID。 */
    private Long tenantId;
    /** 统计对应的当前号码代次。 */
    private Integer generation;
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
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;
    /** 最近校准完成时间（epoch 毫秒）。 */
    private Long reconciledAt;

    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long dataPackageId) { this.dataPackageId = dataPackageId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
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
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Long reconciledAt) { this.reconciledAt = reconciledAt; }
}
