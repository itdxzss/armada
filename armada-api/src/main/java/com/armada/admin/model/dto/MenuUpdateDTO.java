package com.armada.admin.model.dto;

/** 修改菜单节点参数。 */
public record MenuUpdateDTO(
        Long parentId,
        String menuName,
        String menuKey,
        String menuType,
        String routePath,
        String componentPath,
        String permKey,
        String icon,
        Integer sortNo) {
}
