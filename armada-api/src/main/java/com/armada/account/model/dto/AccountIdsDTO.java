package com.armada.account.model.dto;

import java.util.List;

/**
 * 批量操作通用请求体:账号 ID 列表。
 */
public record AccountIdsDTO(List<Long> ids) {
}
