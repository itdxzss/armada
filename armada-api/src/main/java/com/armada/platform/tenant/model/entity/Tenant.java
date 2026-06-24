package com.armada.platform.tenant.model.entity;

import java.time.LocalDateTime;

/** 租户实体,映射 tenant 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。 */
public class Tenant {

    private Long id;            // 主键,即 tenant_id
    private String tenantCode;  // 租户码
    private String name;        // 租户名
    private Integer status;     // 1=启用 0=停用
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
