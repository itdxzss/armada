package com.armada.resource.model.entity;

/** 拉群数据包数据库/查询数据。 */
public class GroupDataPackage {
    /** 包ID。 */
    private Long id;
    /** 租户ID。 */
    private Long tenantId;
    /** 名称。 */
    private String name;
    /** 备注。 */
    private String remark;
    /** 当前号码代次。 */
    private Integer generation;
    /** 元数据乐观锁。 */
    private Integer version;
    /** 首次/最近任务使用时间。 */
    private Long lastUsedAt;
    /** 创建人。 */
    private Long createdBy;
    /** 创建时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    /** 软删时间。 */
    private Long deletedAt;
    /** 读取包ID。 */
    public Long getId() { return id; }
    /** 设置包ID。 */
    public void setId(Long value) { id = value; }
    /** 读取租户ID。 */
    public Long getTenantId() { return tenantId; }
    /** 设置租户ID。 */
    public void setTenantId(Long value) { tenantId = value; }
    /** 读取名称。 */
    public String getName() { return name; }
    /** 设置名称。 */
    public void setName(String value) { name = value; }
    /** 读取备注。 */
    public String getRemark() { return remark; }
    /** 设置备注。 */
    public void setRemark(String value) { remark = value; }
    /** 读取当前号码代次。 */
    public Integer getGeneration() { return generation; }
    /** 设置当前号码代次。 */
    public void setGeneration(Integer value) { generation = value; }
    /** 读取元数据乐观锁。 */
    public Integer getVersion() { return version; }
    /** 设置元数据乐观锁。 */
    public void setVersion(Integer value) { version = value; }
    /** 读取首次/最近任务使用时间。 */
    public Long getLastUsedAt() { return lastUsedAt; }
    /** 设置首次/最近任务使用时间。 */
    public void setLastUsedAt(Long value) { lastUsedAt = value; }
    /** 读取创建人。 */
    public Long getCreatedBy() { return createdBy; }
    /** 设置创建人。 */
    public void setCreatedBy(Long value) { createdBy = value; }
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 设置创建时间。 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** 读取更新时间。 */
    public Long getUpdatedAt() { return updatedAt; }
    /** 设置更新时间。 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
    /** 读取软删时间。 */
    public Long getDeletedAt() { return deletedAt; }
    /** 设置软删时间。 */
    public void setDeletedAt(Long value) { deletedAt = value; }
}
