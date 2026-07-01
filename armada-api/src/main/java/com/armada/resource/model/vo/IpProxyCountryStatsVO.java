package com.armada.resource.model.vo;

import java.math.BigDecimal;

/**
 * IP 数据统计国家/地区聚合行。
 */
public record IpProxyCountryStatsVO(

        /** 国家/地区中文快照。 */
        String region,

        /** IP 总数。 */
        Long totalIpCount,

        /** 使用中 IP 数。 */
        Long inUseIpCount,

        /** 空闲 IP 数。 */
        Long idleIpCount,

        /** 不可用 IP 数。 */
        Long unavailableIpCount,

        /** 可用率=(空闲+使用中)/总数*100,保留 2 位。 */
        BigDecimal availableRate,

        /** 不可用率=不可用/总数*100,保留 2 位。 */
        BigDecimal unavailableRate,

        /** 国家级最近 IP 抽检时间(epoch 毫秒)。 */
        Long lastSampleCheckAt,

        /** 资源风险枚举值。 */
        String resourceRisk,

        /** 资源风险展示名。 */
        String resourceRiskLabel) {
}
