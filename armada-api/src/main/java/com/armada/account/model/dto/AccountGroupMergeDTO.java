package com.armada.account.model.dto;

import java.util.List;

/**
 * 账号分组合并请求。
 *
 * @param groupIds 按勾选顺序排列的分组 ID，首项为主分组
 */
public record AccountGroupMergeDTO(List<Long> groupIds) {
}
