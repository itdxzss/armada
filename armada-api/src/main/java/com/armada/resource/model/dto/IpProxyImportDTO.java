package com.armada.resource.model.dto;

/**
 * IP 代理批量导入入参（@RequestBody）。国家/协议/来源作为本次全部记录的统一属性，
 * {@code text} 为多行原文，每行一个代理 {@code host:port:username:password}。
 *
 * @param region   本次全部记录的国家/分组中文展示名（具体国家或「混合（不限国家）」）
 * @param protocol 协议码（1=HTTP 2=SOCKS5），必填
 * @param source   来源，必填
 * @param text     多行原文，每行 {@code host:port:username:password}
 */
public record IpProxyImportDTO(
        String region,
        Integer protocol,
        String source,
        String text) {
}
