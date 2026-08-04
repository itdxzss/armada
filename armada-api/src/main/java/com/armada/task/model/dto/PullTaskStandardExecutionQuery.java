package com.armada.task.model.dto;

import com.armada.shared.paging.PageQuery;

/** 普通群链接单群工作台分页查询参数。 */
public class PullTaskStandardExecutionQuery extends PageQuery {

    private String keyword;
    private Integer executionStatus;
    private Integer stage;
    private Integer waitResourceType;
    private Integer manualPaused;

    /** @return 带任务范围且已清理空白关键字的不可变 Mapper 条件 */
    public PullTaskStandardExecutionFilter toFilter(long taskId) {
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new PullTaskStandardExecutionFilter(
                taskId, normalized, executionStatus, stage, waitResourceType, manualPaused);
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
}
