package com.armada.resource.model.vo;

/**
 * 国家级 IP 抽样检测弹框统计。
 *
 * @param region 国家/地区中文快照
 * @param totalIpCount 当前国家 IP 总数量
 * @param availableIpCount 当前国家可用数量;对应空闲 IP
 * @param inUseIpCount 当前国家使用中数量
 * @param unavailableIpCount 当前国家不可用数量
 */
public record IpProxyCountrySampleStatsVO(
        String region,
        Long totalIpCount,
        Long availableIpCount,
        Long inUseIpCount,
        Long unavailableIpCount) {
}
