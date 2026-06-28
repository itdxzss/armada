package com.armada.marketing.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 营销任务列表查询。GET 列表用 {@code @ModelAttribute} 绑定,故为可变 class。
 */
public class MarketingTaskQuery extends PageQuery {

    /** 任务 ID,精准匹配。 */
    private Long id;

    /** 任务名称关键词,模糊匹配。 */
    private String keyword;

    /** 任务状态码。 */
    private Integer status;

    /** 最后发送时间下界(epoch 毫秒)。 */
    private Long startTime;

    /** 最后发送时间上界(epoch 毫秒)。 */
    private Long endTime;

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

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }
}
