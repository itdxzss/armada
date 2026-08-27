package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

/**
 * 账号分组列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class AccountGroupQuery extends PageQuery {

    /** 关键字模糊搜索(匹配分组名称)。 */
    private String keyword;

    /** 精确匹配分组 ID(可选)。 */
    private Long id;

    /** 服务端解析的数据范围；不参与 HTTP 绑定。 */
    private DataScope dataScope;

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

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围，避免被 Spring ModelAttribute 绑定。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
