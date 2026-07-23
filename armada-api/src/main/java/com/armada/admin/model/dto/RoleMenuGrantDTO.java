package com.armada.admin.model.dto;

import java.util.List;

/** 角色菜单与按钮授权参数。 */
public record RoleMenuGrantDTO(List<Long> menuIds) {
}
