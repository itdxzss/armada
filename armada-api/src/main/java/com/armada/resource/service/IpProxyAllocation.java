package com.armada.resource.service;

import com.armada.platform.proxy.ProxyEndpoint;

/**
 * 账号上线分配到的代理结果。
 *
 * <p>这是 resource 域暴露给 account 域的轻量边界:account 域只拿代理主键用于日志定位,
 * 以及协议层可直接使用的 {@link ProxyEndpoint},不接触 ip_proxy mapper/entity。</p>
 */
public record IpProxyAllocation(Long proxyId, ProxyEndpoint endpoint) {
}
