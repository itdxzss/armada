package com.armada.account.model.dto;
import com.armada.shared.paging.PageQuery;
/** 互存任务及方向明细的 SQL 分页查询。 */
public class AccountMutualContactQuery extends PageQuery {
    private Integer status;
    public Integer getStatus() {
        return status;
    }
    public void setStatus(Integer value) {
        status = value;
    }
}
