package com.armada.resource.model.dto;

import com.armada.resource.model.IpProxyAllocationMode;

/**
 * IP 代理批量导入入参（@RequestBody）。国家/协议/来源作为本次全部记录的统一属性，
 * {@code text} 为多行原文，每行一个代理 {@code host:port:username:password}。
 *
 * @param region       旧国家/分组中文展示名,兼容旧前端
 * @param protocol     协议码（1=HTTP 2=SOCKS5），必填
 * @param source       来源，必填
 * @param text         多行原文，每行 {@code host:port:username:password}
 * @param countryValue 新国家下拉提交值:真实国家为 ISO/CLDR 二字母码,混合为 MIXED
 * @param allocationMode 分配方式:smart=智能分配 mixed=混合分组;为空兼容旧前端按 smart 处理
 */
public record IpProxyImportDTO(
        String region,
        Integer protocol,
        String source,
        String text,
        String countryValue,
        String allocationMode) {

    public IpProxyImportDTO(String region, Integer protocol, String source, String text) {
        this(region, protocol, source, text, null, IpProxyAllocationMode.SMART.value());
    }

    public IpProxyImportDTO(String region, Integer protocol, String source, String text, String countryValue) {
        this(region, protocol, source, text, countryValue, IpProxyAllocationMode.SMART.value());
    }
}
