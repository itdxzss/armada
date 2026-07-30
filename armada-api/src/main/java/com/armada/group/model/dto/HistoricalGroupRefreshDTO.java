package com.armada.group.model.dto;

/**
 * 历史群手动刷新请求。
 *
 * @param accountGroupId 当前租户账号组 ID
 */
public record HistoricalGroupRefreshDTO(Long accountGroupId) {
}
