package com.armada.task.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 进群任务列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 *
 * <p>所有字段可选;非 null/空 时由 {@link #toFilter()} 转 {@link JoinTaskFilter} 下推 SQL WHERE(禁内存分页)。</p>
 */
public class JoinTaskQuery extends PageQuery {

    /** 关键字:模糊匹配任务名或链接文本(可选)。 */
    private String keyword;

    /** 任务状态英文码:DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED(可选)。 */
    private String status;

    /** 账号分组 id:命中 account_group_ids JSON 快照(可选)。 */
    private Long groupId;

    /** 分配方式英文码:FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK(可选)。 */
    private String distributionMode;

    /** 进群间隔展示标签精确匹配(如 "10-20s",可选)。 */
    private String interval;

    /** 创建时间区间下界(epoch 毫秒,含,可选)。 */
    private Long dateFrom;

    /** 创建时间区间上界(epoch 毫秒,不含,可选)。 */
    private Long dateTo;

    /** 组装 SQL 下推用的筛选条件对象。 */
    public JoinTaskFilter toFilter() {
        return new JoinTaskFilter(keyword, status, groupId, distributionMode, interval, dateFrom, dateTo);
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getDistributionMode() {
        return distributionMode;
    }

    public void setDistributionMode(String distributionMode) {
        this.distributionMode = distributionMode;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public Long getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(Long dateFrom) {
        this.dateFrom = dateFrom;
    }

    public Long getDateTo() {
        return dateTo;
    }

    public void setDateTo(Long dateTo) {
        this.dateTo = dateTo;
    }
}
