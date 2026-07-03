package com.armada.resource.service;

/**
 * 账号上线代理分配请求。
 *
 * <p>account 域只把账号 ID、代理国家偏好和是否允许落到其它真实国家传给 resource 域。
 * resource 域据此按「指定国家 → 混合」或「指定国家 → 混合 → 其它国家」优先级选择空闲代理。</p>
 *
 * @param accountId                账号主键
 * @param preferredRegion          导入账号时选择或按区号解析出的 IP 国家;为空或「混合（不限国家）」时混合池优先
 * @param allowOtherRegionFallback 是否允许指定国家和混合池都无可用代理后继续落到其它真实国家
 */
public record IpProxyAllocationRequest(
        Long accountId,
        String preferredRegion,
        boolean allowOtherRegionFallback) {
}
