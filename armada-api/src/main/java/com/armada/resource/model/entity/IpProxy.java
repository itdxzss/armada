package com.armada.resource.model.entity;

import java.time.LocalDateTime;

/**
 * IP 代理实体，映射 ip_proxy 表一行。普通类 + getter/setter（无 Lombok），Mapper 直出。
 *
 * <p>{@code host} 是代理网关地址（如 geo.iproyal.com），<b>不是</b>真实出口 IP；粘性会话 token 内嵌在
 * {@code password} 段。{@code protocol}/{@code status}/{@code ownership} 为 tinyint 码，对应
 * {@code ProxyProtocol}/{@code IpProxyStatus}/{@code ProxyOwnership} 枚举。</p>
 */
public class IpProxy {

    /** 主键。 */
    private Long id;

    /** 租户 ID；NULL=平台共享池。 */
    private Long tenantId;

    /** 代理网关地址（如 geo.iproyal.com），非真实出口 IP。 */
    private String host;

    /** 代理端口。 */
    private Integer port;

    /** 协议码:1=HTTP 2=SOCKS5（见 {@code ProxyProtocol}）。 */
    private Integer protocol;

    /** 鉴权用户名。 */
    private String username;

    /** 鉴权密码（含 _session/_lifetime 段，原样透传协议层）。 */
    private String password;

    /** 国家/分组中文展示名（「印度」「混合（不限国家）」）。 */
    private String region;

    /** 状态码:1=空闲 2=使用中 3=不可用（见 {@code IpProxyStatus}）。 */
    private Integer status;

    /** 来源（服务商/批次，自由文本）。 */
    private String source;

    /** 归属码:1=租户自有 2=平台池 3=租借（见 {@code ProxyOwnership}）。 */
    private Integer ownership;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删时间；NULL=未删。 */
    private LocalDateTime deletedAt;

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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getProtocol() {
        return protocol;
    }

    public void setProtocol(Integer protocol) {
        this.protocol = protocol;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getOwnership() {
        return ownership;
    }

    public void setOwnership(Integer ownership) {
        this.ownership = ownership;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
