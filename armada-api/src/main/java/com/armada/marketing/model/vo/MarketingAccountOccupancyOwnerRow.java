package com.armada.marketing.model.vo;

/**
 * 普通营销账号当前占用方查询投影。
 *
 * <p>占用表只保存当前租约；任务名称和计划结束时间从 marketing_task 联查，避免同一事实多处存储。</p>
 */
public class MarketingAccountOccupancyOwnerRow {

    private Long accountId;
    private Long marketingTaskId;
    private String taskName;
    private Long taskEndAt;
    private Long occupiedAt;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Long getTaskEndAt() {
        return taskEndAt;
    }

    public void setTaskEndAt(Long taskEndAt) {
        this.taskEndAt = taskEndAt;
    }

    public Long getOccupiedAt() {
        return occupiedAt;
    }

    public void setOccupiedAt(Long occupiedAt) {
        this.occupiedAt = occupiedAt;
    }
}
