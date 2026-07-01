package com.armada.resource.model.entity;

import com.armada.resource.model.IpProxyAllocationMode;
import java.math.BigDecimal;

/**
 * IP 代理实体，映射 ip_proxy 表一行。普通类 + getter/setter（无 Lombok），Mapper 直出。
 *
 * <p>{@code host} 是代理网关地址（如 geo.iproyal.com），<b>不是</b>真实出口 IP；粘性会话 token 内嵌在
 * {@code password} 段。{@code protocol}/{@code status}/{@code ownership} 为 tinyint 码，对应
 * {@code ProxyProtocol}/{@code IpProxyStatus}/{@code ProxyOwnership} 枚举;{@code allocationMode} 为导入时选择的
 * IP 分配方式。</p>
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

    /** 当前绑定账号 ID;NULL=未绑定。 */
    private Long boundAccountId;

    /** 绑定时间(epoch毫秒);NULL=未绑定。 */
    private Long boundAt;

    /** 最近抽检时间(epoch毫秒);NULL=尚未抽检。 */
    private Long lastSampleCheckAt;

    /** 检测出的 ISO2 国家码。 */
    private String detectedCountryCode;

    /** 真实出口公网 IP。 */
    private String outboundIp;

    /** 检测出的地理位置。 */
    private String detectedLocation;

    /** 检测出的 ISP。 */
    private String detectedIsp;

    /** 检测纬度。 */
    private BigDecimal detectedLatitude;

    /** 检测经度。 */
    private BigDecimal detectedLongitude;

    /** 检测失败次数。 */
    private Integer checkFailCount;

    /** 最近一次检测失败原因。 */
    private String lastCheckError;

    /** 检测生命周期:0=检测中 1=成功 2=失败。 */
    private Integer checkStatus;

    /** WhatsApp 连通性检测生命周期:0=检测中 1=成功 2=失败。 */
    private Integer whatsappCheckStatus;

    /** WhatsApp 探测 HTTP 状态码;NULL=尚未收到官方响应。 */
    private Integer whatsappHttpStatus;

    /** WhatsApp 连通性检测失败原因。 */
    private String whatsappCheckError;

    /** 来源（服务商/批次，自由文本）。 */
    private String source;

    /** 归属码:1=租户自有 2=平台池 3=租借（见 {@code ProxyOwnership}）。 */
    private Integer ownership;

    /** 分配方式:smart=智能分配 mixed=混合分组。 */
    private String allocationMode = IpProxyAllocationMode.SMART.value();

    /** 备注。 */
    private String remark;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
    private Long updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删时间(epoch毫秒);NULL=未删。 */
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

    public Long getBoundAccountId() {
        return boundAccountId;
    }

    public void setBoundAccountId(Long boundAccountId) {
        this.boundAccountId = boundAccountId;
    }

    public Long getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Long boundAt) {
        this.boundAt = boundAt;
    }

    public Long getLastSampleCheckAt() {
        return lastSampleCheckAt;
    }

    public void setLastSampleCheckAt(Long lastSampleCheckAt) {
        this.lastSampleCheckAt = lastSampleCheckAt;
    }

    public String getDetectedCountryCode() {
        return detectedCountryCode;
    }

    public void setDetectedCountryCode(String detectedCountryCode) {
        this.detectedCountryCode = detectedCountryCode;
    }

    public String getOutboundIp() {
        return outboundIp;
    }

    public void setOutboundIp(String outboundIp) {
        this.outboundIp = outboundIp;
    }

    public String getDetectedLocation() {
        return detectedLocation;
    }

    public void setDetectedLocation(String detectedLocation) {
        this.detectedLocation = detectedLocation;
    }

    public String getDetectedIsp() {
        return detectedIsp;
    }

    public void setDetectedIsp(String detectedIsp) {
        this.detectedIsp = detectedIsp;
    }

    public BigDecimal getDetectedLatitude() {
        return detectedLatitude;
    }

    public void setDetectedLatitude(BigDecimal detectedLatitude) {
        this.detectedLatitude = detectedLatitude;
    }

    public BigDecimal getDetectedLongitude() {
        return detectedLongitude;
    }

    public void setDetectedLongitude(BigDecimal detectedLongitude) {
        this.detectedLongitude = detectedLongitude;
    }

    public Integer getCheckFailCount() {
        return checkFailCount;
    }

    public void setCheckFailCount(Integer checkFailCount) {
        this.checkFailCount = checkFailCount;
    }

    public String getLastCheckError() {
        return lastCheckError;
    }

    public void setLastCheckError(String lastCheckError) {
        this.lastCheckError = lastCheckError;
    }

    public Integer getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(Integer checkStatus) {
        this.checkStatus = checkStatus;
    }

    public Integer getWhatsappCheckStatus() {
        return whatsappCheckStatus;
    }

    public void setWhatsappCheckStatus(Integer whatsappCheckStatus) {
        this.whatsappCheckStatus = whatsappCheckStatus;
    }

    public Integer getWhatsappHttpStatus() {
        return whatsappHttpStatus;
    }

    public void setWhatsappHttpStatus(Integer whatsappHttpStatus) {
        this.whatsappHttpStatus = whatsappHttpStatus;
    }

    public String getWhatsappCheckError() {
        return whatsappCheckError;
    }

    public void setWhatsappCheckError(String whatsappCheckError) {
        this.whatsappCheckError = whatsappCheckError;
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

    public String getAllocationMode() {
        return allocationMode;
    }

    public void setAllocationMode(String allocationMode) {
        this.allocationMode = allocationMode;
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
