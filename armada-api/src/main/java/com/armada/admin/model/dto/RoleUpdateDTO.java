package com.armada.admin.model.dto;

/** 修改角色参数；角色编码创建后不可修改。 */
public record RoleUpdateDTO(String roleName, String remark) {
}
