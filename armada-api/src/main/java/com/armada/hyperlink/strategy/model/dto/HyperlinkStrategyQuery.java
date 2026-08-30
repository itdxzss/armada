package com.armada.hyperlink.strategy.model.dto;

import com.armada.shared.paging.PageQuery;

/** 超链策略名称、任务模式、启用状态与分页查询参数。 */
public class HyperlinkStrategyQuery extends PageQuery {

    /** 策略名称模糊筛选。 */
    private String name;
    /** API 任务模式筛选。 */
    private String taskMode;
    /** 启用状态筛选。 */
    private Boolean enabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTaskMode() {
        return taskMode;
    }

    public void setTaskMode(String taskMode) {
        this.taskMode = taskMode;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
