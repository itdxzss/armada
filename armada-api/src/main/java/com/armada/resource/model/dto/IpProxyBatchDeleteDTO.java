package com.armada.resource.model.dto;

import java.util.List;

/**
 * IP 代理批量删除入参（@RequestBody）：按 id 列表软删。
 *
 * @param ids 待删除的 IP 代理 ID 列表
 */
public record IpProxyBatchDeleteDTO(List<Long> ids) {
}
