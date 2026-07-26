package com.armada.admin.model.dto;

/** 新建菜单节点参数。 */
public record MenuCreateDTO(
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
