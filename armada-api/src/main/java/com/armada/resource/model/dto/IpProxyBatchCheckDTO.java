package com.armada.resource.model.dto;

import java.util.List;

/**
 * IP 代理批量检测入参。
 *
 * @param ids 待检测的 ip_proxy 主键列表
 */
public record IpProxyBatchCheckDTO(List<Long> ids) {
}
