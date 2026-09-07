package com.armada.marketing.model.dto;

import com.armada.shared.paging.PageQuery;

/** 剧本任务 SQL 分页条件。 */
public class ScriptMarketingQuery extends PageQuery {
    /** 名称筛选。 */ private String keyword;
    /** 可选状态。 */ private Integer status;
    /** 读取名称。 */ public String getKeyword() { return keyword; }
    /** 设置名称。 */ public void setKeyword(String value) { keyword = value; }
    /** 读取状态。 */ public Integer getStatus() { return status; }
    /** 设置状态。 */ public void setStatus(Integer value) { status = value; }
}
