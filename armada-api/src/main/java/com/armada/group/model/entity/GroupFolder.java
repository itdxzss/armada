package com.armada.group.model.entity;

/** 群组列表运营分组，映射 {@code group_folder}。 */
public class GroupFolder {

    /** 主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 分组名称。 */
    private String name;

    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** 更新时间，epoch 毫秒。 */
    private Long updatedAt;

    /** 创建人用户 ID。 */
    private Long createdBy;

    /** 软删除时间，epoch 毫秒。 */
    private Long deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
