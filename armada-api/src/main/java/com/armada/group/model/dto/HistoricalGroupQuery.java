package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/** 账号组历史群分页查询。 */
public class HistoricalGroupQuery extends PageQuery {

    /** 账号组 ID。 */
    private Long accountGroupId;

    /**
     * 返回待查询账号组 ID。
     *
     * @return 账号组 ID
     */
    public Long getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * 设置待查询账号组 ID。
     *
     * @param accountGroupId 账号组 ID
     */
    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }
}
