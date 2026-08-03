package com.armada.group.model.dto;

import java.util.List;

/**
 * 批量设置或取消群组列表运营分组请求。
 *
 * @param ids 群组 ID
 * @param folderId 目标运营分组 ID；null 表示取消分组
 */
public record GroupFolderAssignDTO(List<Long> ids, Long folderId) {
}
