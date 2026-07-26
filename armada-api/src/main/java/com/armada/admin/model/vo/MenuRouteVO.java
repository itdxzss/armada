package com.armada.admin.model.vo;

import java.util.List;

/** 登录接入后供前端消费的动态路由节点。 */
public record MenuRouteVO(
        String path,
        String name,
        String component,
        MenuRouteMetaVO meta,
        List<MenuRouteVO> children) {
}
