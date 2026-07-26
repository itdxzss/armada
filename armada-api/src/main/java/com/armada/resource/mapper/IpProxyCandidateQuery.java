package com.armada.resource.mapper;

import java.util.List;

/**
 * 上线代理候选查询参数。
 *
 * <p>查询只读取当前租户仍为 IDLE 的候选行，不提前加行锁。{@code excludedProxyIds}
 * 仅承载删除代理重登等固定排除集合，不能追加本批已分配代理。</p>
 *
 * @param tenantId 当前租户 ID
 * @param idleStatus 空闲代理状态码
 * @param preferredRegion 首选国家；为空时混合池优先
 * @param mixedRegion 混合池展示值
 * @param excludedProxyIds 固定排除的代理 ID
 * @param allowOtherRegionFallback 是否允许回退到其它国家
 * @param limit 本轮最多读取的候选数量
 */
public record IpProxyCandidateQuery(
        Long tenantId,
        int idleStatus,
        String preferredRegion,
        String mixedRegion,
        List<Long> excludedProxyIds,
        boolean allowOtherRegionFallback,
        int limit
) {
}
