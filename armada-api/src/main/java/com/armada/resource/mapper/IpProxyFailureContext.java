package com.armada.resource.mapper;

/** 协议代理失败的精确绑定与事件水位，不包含代理凭据。 */
public record IpProxyFailureContext(Long accountId, Long proxyId, long failedAt) {
}
