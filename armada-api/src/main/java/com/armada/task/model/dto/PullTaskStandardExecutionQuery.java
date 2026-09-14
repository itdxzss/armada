package com.armada.task.model.dto;

import com.armada.shared.paging.PageQuery;

/** 普通群链接单群工作台分页查询参数。 */
public class PullTaskStandardExecutionQuery extends PageQuery {

    private String keyword;
    private Integer executionStatus;
    private Integer stage;
    private Integer waitResourceType;
    private Integer manualPaused;
    /** 精确匹配当前阻塞原因，例如等待父任务并发名额。 */
    private String reasonCode;

    /** @return 带任务范围且已清理空白关键字的不可变 Mapper 条件 */
    public PullTaskStandardExecutionFilter toFilter(long taskId) {
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new PullTaskStandardExecutionFilter(
                taskId, normalized, executionStatus, stage, waitResourceType, manualPaused,
                reasonCode == null || reasonCode.isBlank() ? null : reasonCode.trim());
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Integer getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(Integer value) { executionStatus = value; }
    public Integer getStage() { return stage; }
    public void setStage(Integer stage) { this.stage = stage; }
    public Integer getWaitResourceType() { return waitResourceType; }
    public void setWaitResourceType(Integer value) { waitResourceType = value; }
    public Integer getManualPaused() { return manualPaused; }
    public void setManualPaused(Integer manualPaused) { this.manualPaused = manualPaused; }
    /** @return 当前阻塞原因的精确筛选值 */
    public String getReasonCode() { return reasonCode; }
    /** @param reasonCode 当前阻塞原因的精确筛选值，空白表示不限 */
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
}
