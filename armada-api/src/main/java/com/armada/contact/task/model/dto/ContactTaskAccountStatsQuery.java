package com.armada.contact.task.model.dto;

import java.util.Set;

/** 账号统计排序分页参数；保留旧接口无效排序回退到 ID 的行为。 */
public record ContactTaskAccountStatsQuery(Long taskId, String sortBy, String sortOrder,
        long offset, int limit) {
    private static final Set<String> SORTS = Set.of("needSendNum", "sentNum", "failNum",
            "processedNum", "deliveredNum", "readNum", "unknownNum", "skippedNum");
    /** 对外部查询参数做白名单及边界归一化。 */
    public ContactTaskAccountStatsQuery {
        sortBy = SORTS.contains(sortBy == null ? "" : sortBy) ? sortBy : "id";
        sortOrder = "asc".equalsIgnoreCase(sortOrder == null ? "" : sortOrder.trim()) ? "asc" : "desc";
        offset = Math.max(0, offset);
        limit = Math.min(200, Math.max(1, limit));
    }
}
