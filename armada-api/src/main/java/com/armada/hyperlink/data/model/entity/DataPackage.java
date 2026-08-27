package com.armada.hyperlink.data.model.entity;

/** 数据包主数据实体。 */
public class DataPackage {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 同一租户内未删除包的唯一名称。 */
    private String packageName;
    /** 可空备注。 */
    private String remark;
    /** 当前可见号码代次。 */
    private Integer currentGeneration;
    /** 当前代号码总数快照。 */
    private Integer phoneCount;
    /** 元数据乐观锁版本。 */
    private Integer version;
    /** 创建用户 ID。 */
    private Long createdBy;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;
    /** 软删时间（epoch 毫秒）。 */
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getCurrentGeneration() { return currentGeneration; }
    public void setCurrentGeneration(Integer currentGeneration) { this.currentGeneration = currentGeneration; }
    public Integer getPhoneCount() { return phoneCount; }
    public void setPhoneCount(Integer phoneCount) { this.phoneCount = phoneCount; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
