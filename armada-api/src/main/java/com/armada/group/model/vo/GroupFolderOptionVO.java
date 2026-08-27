package com.armada.group.model.vo;

/**
 * 群组运营分组下拉选项。
 *
 * @param id 分组 ID
 * @param ownerUserId 归属用户 ID；历史待分配数据为空
 * @param name 分组名称
 */
public record GroupFolderOptionVO(long id, Long ownerUserId, String name) {
}
