package com.armada.account.model.dto;

/**
 * 账号分组拆分请求。
 *
 * @param groupId   原分组 ID
 * @param groupCount 拆分后分组数量
 */
public record AccountGroupSplitDTO(Long groupId, Integer groupCount) {
}
