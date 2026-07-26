package com.armada.admin.model.vo;

import java.util.List;

/** 菜单管理树节点。 */
public record MenuTreeVO(
        Long id,
        Long parentId,
        String menuName,
        String menuKey,
        String menuType,
        String routePath,
        String componentPath,
        String permKey,
        String icon,
        Integer sortNo,
        Integer status,
        List<MenuTreeVO> children) {
}
