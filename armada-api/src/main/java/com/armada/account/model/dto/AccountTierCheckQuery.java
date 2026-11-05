package com.armada.account.model.dto;
import com.armada.shared.paging.PageQuery;
/** 批次/明细 SQL 分页筛选。 */
public class AccountTierCheckQuery extends PageQuery {
    private String keyword;
    private Integer mode;
    private Integer status;
    private String filter;
    public String getKeyword() { return keyword; }
    public void setKeyword(String v) { keyword = v; }
    public Integer getMode() { return mode; }
    public void setMode(Integer v) { mode = v; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer v) { status = v; }
    public String getFilter() { return filter; }
    public void setFilter(String v) { filter = v; }
}
