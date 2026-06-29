package com.armada.resource.service;

/**
 * 账号上线代理分配请求。
 *
 * <p>account 域只把账号 ID 和导入时选择的 IP 国家传给 resource 域。
 * resource 域据此按「指定国家 → 混合 → 其它国家」优先级选择空闲代理。</p>
 *
 * @param accountId       账号主键
 * @param preferredRegion 导入账号时选择的 IP 国家;为空或「混合（不限国家）」时混合池优先
 */
public record IpProxyAllocationRequest(Long accountId, String preferredRegion) {
}
