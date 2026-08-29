package com.armada.hyperlink.task.model.dto;

import com.armada.shared.paging.PageQuery;

/** 超链任务列表筛选；导出复用筛选字段但忽略分页。 */
public class HyperlinkTaskListQuery extends PageQuery {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private String taskName;
    private Integer runStatus;
    private String taskMode;
    private String countryIso2;
    private Long createdAtStart;
    private Long createdAtEnd;

    /** Service 规范化后的租户边界。 */
    private Long tenantId;
    /** Service 转义后的 LIKE 文本，不含两侧通配符。 */
    private String taskNameLike;
    /** Service 解析后的数据库任务模式码。 */
    private Integer taskModeCode;
    /** 是否筛选冻结国家快照中的未知国家。 */
    private boolean unknownCountry;

    public HyperlinkTaskListQuery() {
        super.setPageSize(DEFAULT_PAGE_SIZE);
    }

    @Override
    public void setPageSize(int pageSize) {
        super.setPageSize(pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE));
    }

    public String getTaskName() { return taskName; }
    public void setTaskName(String value) { this.taskName = value; }
    public Integer getRunStatus() { return runStatus; }
    public void setRunStatus(Integer value) { this.runStatus = value; }
    public String getTaskMode() { return taskMode; }
    public void setTaskMode(String value) { this.taskMode = value; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String value) { this.countryIso2 = value; }
    public Long getCreatedAtStart() { return createdAtStart; }
    public void setCreatedAtStart(Long value) { this.createdAtStart = value; }
    public Long getCreatedAtEnd() { return createdAtEnd; }
    public void setCreatedAtEnd(Long value) { this.createdAtEnd = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public String getTaskNameLike() { return taskNameLike; }
    public void setTaskNameLike(String value) { this.taskNameLike = value; }
    public Integer getTaskModeCode() { return taskModeCode; }
    public void setTaskModeCode(Integer value) { this.taskModeCode = value; }
    public boolean isUnknownCountry() { return unknownCountry; }
    public void setUnknownCountry(boolean value) { this.unknownCountry = value; }
}
