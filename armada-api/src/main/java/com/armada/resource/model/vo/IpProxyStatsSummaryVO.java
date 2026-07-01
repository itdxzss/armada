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

        /** 有活跃 IP 的真实支持国家数,不包含混合池。 */
        Long coveredRegionCount,

        /** 系统支持的真实国家总数,不包含混合池。 */
        Long supportedCountryCount,

        /** 系统支持但当前没有 IP 的真实国家数,不包含混合池。 */
        Long noIpCountryCount) {
}
