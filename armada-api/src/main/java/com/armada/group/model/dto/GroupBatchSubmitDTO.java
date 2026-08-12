package com.armada.group.model.dto;

import java.util.List;

/**
 * 群组列表批量操作提交请求。
 *
 * @param ids 已勾选群入口 ID;后端负责去重与逐项校验
 * @param requestId 前端幂等键;同租户重复提交返回已有任务
 */
public record GroupBatchSubmitDTO(List<Long> ids, String requestId) {
}
