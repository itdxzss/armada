package com.armada.group.model.dto;

import java.util.List;

/**
 * 批量删除群组列表运营分组请求。
 *
 * @param ids 待删除分组 ID
 */
public record GroupFolderBatchDeleteDTO(List<Long> ids) {
}
