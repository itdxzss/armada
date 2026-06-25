package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 账号分组列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class AccountGroupQuery extends PageQuery {

    /** 关键字模糊搜索(匹配分组名称)。 */
    private String keyword;

    /** 精确匹配分组 ID(可选)。 */
    private Long id;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
