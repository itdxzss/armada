package com.armada.resource.model.vo;

/**
 * Mapper 投影:IP 数据统计国家/地区聚合原始计数。
 *
 * <p>普通类 + getter/setter,供 MyBatis resultType 直接映射。</p>
 */
public class IpProxyCountryStatsRow {

    /** 国家/地区中文快照。 */
    private String region;

    /** IP 总数。 */
    private Long totalIpCount;

    /** 使用中 IP 数。 */
    private Long inUseIpCount;

    /** 空闲 IP 数。 */
    private Long idleIpCount;

    /** 不可用 IP 数。 */
    private Long unavailableIpCount;

    /** 国家级最近 IP 抽检时间(epoch毫秒);未抽检时为 null。 */
    private Long lastSampleCheckAt;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Long getTotalIpCount() {
        return totalIpCount;
    }

    public void setTotalIpCount(Long totalIpCount) {
        this.totalIpCount = totalIpCount;
    }

    public Long getInUseIpCount() {
        return inUseIpCount;
    }

    public void setInUseIpCount(Long inUseIpCount) {
        this.inUseIpCount = inUseIpCount;
    }

    public Long getIdleIpCount() {
        return idleIpCount;
    }

    public void setIdleIpCount(Long idleIpCount) {
        this.idleIpCount = idleIpCount;
    }

    public Long getUnavailableIpCount() {
        return unavailableIpCount;
    }

    public void setUnavailableIpCount(Long unavailableIpCount) {
        this.unavailableIpCount = unavailableIpCount;
    }

    public Long getLastSampleCheckAt() {
        return lastSampleCheckAt;
    }

    public void setLastSampleCheckAt(Long lastSampleCheckAt) {
        this.lastSampleCheckAt = lastSampleCheckAt;
    }
}
