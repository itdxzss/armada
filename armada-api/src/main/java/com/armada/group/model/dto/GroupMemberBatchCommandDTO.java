package com.armada.group.model.dto;

import java.util.List;

/**
 * 群成员批量操作请求。
 *
 * @param jids 目标成员 JID 列表
 */
public record GroupMemberBatchCommandDTO(List<String> jids) {
}
