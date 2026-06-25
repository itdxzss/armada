package com.armada.account.model.entity;

/**
 * 账号分组实体,映射 account_group 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 */
public class AccountGroup {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写)。 */
    private Long tenantId;

    /** 分组名称(租户内不可重复)。 */
    private String name;

    /** 备注。 */
    private String remark;

    /** 是否系统内置:1=是,0=否。系统内置分组不可删除。 */
    private Integer systemBuiltin;

    /** 创建时间(epoch 毫秒,UTC)。insert 时由调用方写入,表无 DB 默认值。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒,UTC)。insert/update 时由调用方写入。 */
    private Long updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删除时间(epoch 毫秒);NULL=未删。 */
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
