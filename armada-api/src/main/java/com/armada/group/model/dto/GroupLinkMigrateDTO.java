package com.armada.group.model.dto;

import java.util.List;

/**
 * 群链接批量迁移分组请求体。
 *
 * @param linkIds       待迁移的群链接 ID 列表(1..100)
 * @param targetLabelId 目标WS链接分组 ID
 */
public record GroupLinkMigrateDTO(List<Long> linkIds, Long targetLabelId) {
}
