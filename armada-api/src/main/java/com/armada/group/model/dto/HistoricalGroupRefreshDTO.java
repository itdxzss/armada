package com.armada.group.model.dto;

/**
 * 历史群手动刷新请求。
 *
 * @param accountId 当前租户操作账号 ID
 */
public record HistoricalGroupRefreshDTO(Long accountId) {
}
