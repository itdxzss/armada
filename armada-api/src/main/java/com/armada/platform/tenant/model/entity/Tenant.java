package com.armada.platform.tenant.model.entity;

/**
 * 租户实体,映射 tenant 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。
 *
 * <p>租户用 {@code status}(启用/停用)而非 {@code deleted_at}:租户是平台级实体不做软删,
 * 停用即冻结其下业务访问,故 tenant 表不设 deleted_at。</p>
 */
public class Tenant {

    /** 主键,即各业务表里用的 tenant_id。 */
    private Long id;

    /** 租户码(前端 {@code X-Tenant-Code} 头 / 登录入参;租户内唯一)。 */
    private String tenantCode;

    /** 租户名(展示用)。 */
    private String name;

    /** 状态:1=启用 0=停用。停用租户视同不存在,不可登录、其下业务一律拒绝。 */
    private Integer status;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
