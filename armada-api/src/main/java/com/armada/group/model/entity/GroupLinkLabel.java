package com.armada.group.model.entity;

/**
 * WS链接分组实体,映射 group_link_label 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。
 */
public class GroupLinkLabel {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写)。 */
    private Long tenantId;

    /** WS链接分组名称(租户内不可重复)。 */
    private String name;

    /** 使用国家/区域展示名(可「混合」)。 */
    private String region;

    /** 备注。 */
    private String remark;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
    private Long updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删除时间(epoch毫秒);NULL=未删。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
