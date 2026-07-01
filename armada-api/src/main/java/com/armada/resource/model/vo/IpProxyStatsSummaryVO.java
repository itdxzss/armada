package com.armada.resource.model.vo;

/**
 * IP 数据统计总览。
 */
public record IpProxyStatsSummaryVO(

        /** 活跃 IP 总数。 */
        Long totalIpCount,

        /** 使用中 IP 数。 */
        Long inUseIpCount,

        /** 空闲 IP 数。 */
        Long idleIpCount,

        /** 不可用 IP 数。 */
        Long unavailableIpCount,

        /** 有活跃 IP 的国家/地区数。 */
        Long coveredRegionCount) {
}
