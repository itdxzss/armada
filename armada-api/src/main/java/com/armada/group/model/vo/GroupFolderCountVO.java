package com.armada.group.model.vo;

/**
 * 按群组列表归属聚合的未删除群入口数量，包含所有健康状态。
 *
 * @param folderId 当前分组 ID；null 表示未分组
 * @param groupCount 群组列表记录数
 */
public record GroupFolderCountVO(Long folderId, long groupCount) {
}
