package com.armada.marketing.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

public class GroupCreationMarketingTaskQuery extends PageQuery {

    private Long id;
    private String keyword;
    private Integer status;
    /** 服务端从可信身份注入的数据范围，不参与 HTTP 绑定。 */
    private DataScope dataScope;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
