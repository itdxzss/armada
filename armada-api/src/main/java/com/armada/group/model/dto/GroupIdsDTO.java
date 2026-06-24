package com.armada.group.model.dto;

import java.util.List;

/**
 * 批量删除通用请求体:ID 列表。
 */
public record GroupIdsDTO(List<Long> ids) {
}
