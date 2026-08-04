package com.armada.task.model.entity;

import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskType;

/** 拉群任务公共主表实体，映射 {@code pull_task}。 */
public class PullTask {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 公共任务类型。 */
    private PullTaskType taskType;

    /** 拉群营销群组来源；普通任务为空。 */
    private PullTaskGroupSource groupSource;

    /** 任务名称。 */
    private String taskName;

    /** 配置的群名称。 */
    private String groupName;

    /** 普通拉群任务模式。 */
    private String mode;

    /** 当前状态码。 */
    private String status;

    /** 当前主要业务阶段。 */
    private String primaryStage;

    /** 当前阻塞、暂停或停止原因。 */
    private String blockingReason;

    /** 首次真实启动时间(epoch 毫秒)。 */
    private Long startedAt;

    /** 进入 COMPLETED 或 ENDED 的时间(epoch 毫秒)。 */
    private Long finishedAt;

    /** 生命周期更新乐观锁版本号。 */
    private Integer version;

    /** 任务配置群组数。 */
    private int groupCount;

    /** 预计拉人数量。 */
    private int expectedPullCount;

    /** 操作员展示名称快照。 */
    private String operatorName;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    /** 最近一次真实业务执行时间(epoch 毫秒)。 */
    private Long lastBusinessExecutedAt;

    /** 备注。 */
    private String remark;

    /** 创建人用户 ID；"每用户一条草稿"的查询键。 */
    private Long createdBy;

    /** 旧模式任务配置快照 JSON；普通群链接标准任务保持 {@code {}}。 */
    private String configJson;

    /** 软删时间(epoch 毫秒)。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public PullTaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(PullTaskType taskType) {
        this.taskType = taskType;
    }

    public PullTaskGroupSource getGroupSource() {
        return groupSource;
    }

    public void setGroupSource(PullTaskGroupSource groupSource) {
        this.groupSource = groupSource;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPrimaryStage() {
        return primaryStage;
    }

    public void setPrimaryStage(String primaryStage) {
        this.primaryStage = primaryStage;
    }

    public String getBlockingReason() {
        return blockingReason;
    }

    public void setBlockingReason(String blockingReason) {
        this.blockingReason = blockingReason;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public int getGroupCount() {
        return groupCount;
    }

    public void setGroupCount(int groupCount) {
        this.groupCount = groupCount;
    }

    public int getExpectedPullCount() {
        return expectedPullCount;
    }

    public void setExpectedPullCount(int expectedPullCount) {
        this.expectedPullCount = expectedPullCount;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getLastBusinessExecutedAt() {
        return lastBusinessExecutedAt;
    }

    public void setLastBusinessExecutedAt(Long lastBusinessExecutedAt) {
        this.lastBusinessExecutedAt = lastBusinessExecutedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
