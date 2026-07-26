package com.armada.admin.model.vo;

import java.util.List;

/** 前端动态路由元数据。 */
public record MenuRouteMetaVO(String title, String icon, Integer rank, List<String> auths) {
}
