package com.armada.task.model.dto;

import java.util.List;

/** 拉群任务批量操作的任务 ID 请求。 */
public record PullTaskIdsDTO(List<Long> ids) {
}
