package com.armada.resource.service;

import com.armada.platform.proxy.ProxyEndpoint;

/**
 * 批量上线时单个账号分配到的代理结果。
 *
 * <p>这是 resource 域返回给 account 域的轻量边界:account 域只关心账号 ID、本次代理 ID
 * 和协议层可用的代理端点,不直接接触 ip_proxy 表结构。</p>
 *
 * @param accountId 账号主键
 * @param proxyId     本次分配的代理主键
 * @param endpoint    协议层上线可使用的代理端点
 * @param proxySource 代理来源展示文本
 */
public record IpProxyAccountAllocation(Long accountId, Long proxyId, ProxyEndpoint endpoint, String proxySource) {
}
