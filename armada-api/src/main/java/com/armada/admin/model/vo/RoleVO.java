package com.armada.admin.model.vo;

/** 角色列表项。 */
public record RoleVO(
        Long id,
        String roleName,
        String roleCode,
        Integer status,
        boolean system,
        String remark,
        long userCount,
        Long createdAt,
        Long updatedAt) {
}
