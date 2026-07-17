package com.armada.group.model.dto;

import com.armada.group.model.enums.GroupPermissionKey;

/**
 * 单项群权限设置请求。
 *
 * @param key     权限键
 * @param enabled 目标开关状态
 */
public record GroupSettingCommandDTO(GroupPermissionKey key, boolean enabled) {
}
