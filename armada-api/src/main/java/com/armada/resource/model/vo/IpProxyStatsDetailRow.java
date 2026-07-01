package com.armada.resource.model.vo;

/**
 * Mapper 投影:IP 数据统计国家/地区明细原始行。
 *
 * <p>Service 再补协议、状态、归属 label 后返回 {@link IpProxyStatsDetailVO}。</p>
 */
public class IpProxyStatsDetailRow {

    /** 代理 ID。 */
    private Long id;

    /** 代理网关地址。 */
    private String proxyHost;

    /** 代理端口。 */
    private Integer proxyPort;

    /** 代理地址 host:port。 */
    private String proxyAddress;

    /** 协议码:1=HTTP 2=SOCKETS。 */
    private Integer protocol;

    /** 国家/地区中文快照。 */
    private String region;

    /** 状态码:1=空闲 2=使用中 3=不可用。 */
    private Integer status;

    /** 当前绑定账号 ID;未绑定时为 null。 */
    private Long boundAccountId;

    /** 来源。 */
    private String source;

    /** 分配方式:smart=智能分配 mixed=混合分组。 */
    private String allocationMode;

    /** 归属码:1=租户自有 2=平台池 3=租借。 */
    private Integer ownership;

    /** 最近抽检时间(epoch 毫秒);未抽检时为 null。 */
    private Long lastSampleCheckAt;

    /** 入库时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 绑定时间(epoch 毫秒);未绑定时为 null。 */
    private Long boundAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyAddress() {
        return proxyAddress;
    }

    public void setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    public Integer getProtocol() {
        return protocol;
    }

    public void setProtocol(Integer protocol) {
        this.protocol = protocol;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getAllocationMode() {
        return allocationMode;
    }

    public void setAllocationMode(String allocationMode) {
        this.allocationMode = allocationMode;
    }

    public Integer getOwnership() {
        return ownership;
    }

    public void setOwnership(Integer ownership) {
        this.ownership = ownership;
    }

    public Long getLastSampleCheckAt() {
        return lastSampleCheckAt;
    }

    public void setLastSampleCheckAt(Long lastSampleCheckAt) {
        this.lastSampleCheckAt = lastSampleCheckAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Long boundAt) {
        this.boundAt = boundAt;
    }
}
