package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * WS链接分组列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkLabelQuery extends PageQuery {

    /** 关键字模糊搜索(匹配分组名称)。 */
    private String keyword;

    /** 精确匹配分组 ID(可选)。 */
    private Long id;

    /** 创建时间范围起(epoch毫秒)。 */
    private Long createdFrom;

    /** 创建时间范围止(epoch毫秒)。 */
    private Long createdTo;

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

    public Long getCreatedFrom() {
        return createdFrom;
    }

    public void setCreatedFrom(Long createdFrom) {
        this.createdFrom = createdFrom;
    }

    public Long getCreatedTo() {
        return createdTo;
    }

    public void setCreatedTo(Long createdTo) {
        this.createdTo = createdTo;
    }
}
