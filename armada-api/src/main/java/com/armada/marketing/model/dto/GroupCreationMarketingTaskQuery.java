package com.armada.marketing.model.dto;

import com.armada.shared.paging.PageQuery;

public class GroupCreationMarketingTaskQuery extends PageQuery {

    private Long id;
    private String keyword;
    private Integer status;

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
}
