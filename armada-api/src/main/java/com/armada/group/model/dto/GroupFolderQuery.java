package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

/** 群组运营分组分页查询参数。 */
public class GroupFolderQuery extends PageQuery {

    /** 分组名称关键字。 */
    private String keyword;

    /** 服务端注入的数据范围；不接受 HTTP 参数绑定。 */
    private DataScope dataScope;

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入可信数据范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? null : keyword.trim();
    }
}
