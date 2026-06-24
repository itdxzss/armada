package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 群链接列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkQuery extends PageQuery {

    /** 所属WS链接分组 ID(必填,列表必须按分组查)。 */
    private Long labelId;

    /** 关键字模糊搜索(匹配链接URL或群名)。 */
    private String keyword;

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }
}
