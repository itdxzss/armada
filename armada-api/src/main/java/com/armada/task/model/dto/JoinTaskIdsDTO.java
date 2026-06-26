package com.armada.task.model.dto;

import java.util.List;

/** 批量操作入参:任务 id 列表(camelCase wire)。 */
public record JoinTaskIdsDTO(List<Long> ids) {
}
