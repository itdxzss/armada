package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/** 群组运营分组分页查询。 */
public class GroupFolderQuery extends PageQuery {

    /** 分组名称关键字。 */
    private String keyword;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? null : keyword.trim();
    }
}
