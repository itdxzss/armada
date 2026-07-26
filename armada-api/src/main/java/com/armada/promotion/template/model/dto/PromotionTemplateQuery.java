package com.armada.promotion.template.model.dto;

import com.armada.shared.paging.PageQuery;

/** 模板管理分页查询。本期没有页面筛选条件，租户范围由 MyBatis 拦截器注入。 */
public class PromotionTemplateQuery extends PageQuery {

    /** 截图页面默认每页展示 20 条。 */
    public PromotionTemplateQuery() {
        setPageSize(20);
    }
}
