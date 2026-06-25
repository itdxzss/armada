package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 账号导入明细列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 *
 * <p>batchId 必传(按批次查明细);filter 可选,默认 all。</p>
 */
public class AccountImportDetailQuery extends PageQuery {

    /** 所属批次 ID(必传)。 */
    private Long batchId;

    /**
     * 结果过滤器:all=全部;success=只看成功(parse_result=1);fail=只看失败(parse_result IN 2,3,4)。
     * 默认 all。
     */
    private String filter = "all";

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = (filter == null || filter.isBlank()) ? "all" : filter;
    }
}
