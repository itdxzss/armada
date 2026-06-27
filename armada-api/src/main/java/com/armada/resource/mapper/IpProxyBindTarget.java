package com.armada.resource.mapper;

/**
 * IP 代理批量绑定目标。
 *
 * <p>仅供 {@link IpProxyMapper} 的批量 SQL 使用,表达「某个代理行绑定到某个账号」。
 * 该对象不放在 service API 中,避免账号域误依赖 ip_proxy 表的批量更新细节。</p>
 *
 * @param proxyId   代理主键
 * @param accountId 账号主键
 */
public record IpProxyBindTarget(Long proxyId, Long accountId) {
}
