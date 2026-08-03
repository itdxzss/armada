package com.armada.task.model.entity;

/** 普通群链接任务冻结执行配置，映射 {@code pull_task_standard_setting}。 */
public class PullTaskStandardSetting {

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 创建后是否自动启动：0 否 1 是。 */
    private Integer autoStart;

    /** 料子内管理员设置时点：1=成员入群后立即 2=本群料子全部终态后。 */
    private Integer materialAdminTiming;

    /** 单次拉人料子人数下限(闭区间，不含站台)。 */
    private Integer pullCountMin;

    /** 单次拉人料子人数上限(闭区间，不含站台)。 */
    private Integer pullCountMax;

    /** 同一拉手账号连续拉人调用的最小间隔(秒)。 */
    private Integer pullIntervalSeconds;

    /** 每条执行行的计划拉手数。 */
    private Integer pullerCountPerGroup;

    /** 每一次拉人调用叠加的站台数。 */
    private Integer stationCountPerCall;

    /** 同一父任务最大同时运行执行行数。 */
    private Integer concurrentGroupCount;

    /** 拉手风控冷却分钟；0 表示不建立定时恢复。 */
    private Integer pullerRiskMinutes;

    /** 任务启动时按管理分组可用账号数冻结的要求管理员人数 N。 */
    private Integer requiredManagerCount;

    /** 管理账号分组 ID(→account_group.id)。 */
    private Long managerGroupId;

    /** 拉手账号分组 ID(→account_group.id)。 */
    private Long pullerGroupId;

    /** 站台账号分组 ID(→account_group.id)。 */
    private Long stationGroupId;

    /** 管理分组名称快照。 */
    private String managerGroupName;

    /** 拉手分组名称快照。 */
    private String pullerGroupName;

    /** 站台分组名称快照。 */
    private String stationGroupName;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAutoStart() { return autoStart; }
    public void setAutoStart(Integer autoStart) { this.autoStart = autoStart; }
    public Integer getMaterialAdminTiming() { return materialAdminTiming; }
    public void setMaterialAdminTiming(Integer materialAdminTiming) {
        this.materialAdminTiming = materialAdminTiming;
    }
    public Integer getPullCountMin() { return pullCountMin; }
    public void setPullCountMin(Integer pullCountMin) { this.pullCountMin = pullCountMin; }
    public Integer getPullCountMax() { return pullCountMax; }
    public void setPullCountMax(Integer pullCountMax) { this.pullCountMax = pullCountMax; }
    public Integer getPullIntervalSeconds() { return pullIntervalSeconds; }
    public void setPullIntervalSeconds(Integer pullIntervalSeconds) {
        this.pullIntervalSeconds = pullIntervalSeconds;
    }
    public Integer getPullerCountPerGroup() { return pullerCountPerGroup; }
    public void setPullerCountPerGroup(Integer pullerCountPerGroup) {
        this.pullerCountPerGroup = pullerCountPerGroup;
    }
    public Integer getStationCountPerCall() { return stationCountPerCall; }
    public void setStationCountPerCall(Integer stationCountPerCall) {
        this.stationCountPerCall = stationCountPerCall;
    }
    public Integer getConcurrentGroupCount() { return concurrentGroupCount; }
    public void setConcurrentGroupCount(Integer concurrentGroupCount) {
        this.concurrentGroupCount = concurrentGroupCount;
    }
    public Integer getPullerRiskMinutes() { return pullerRiskMinutes; }
    public void setPullerRiskMinutes(Integer pullerRiskMinutes) {
        this.pullerRiskMinutes = pullerRiskMinutes;
    }
    public Integer getRequiredManagerCount() { return requiredManagerCount; }
    public void setRequiredManagerCount(Integer requiredManagerCount) {
        this.requiredManagerCount = requiredManagerCount;
    }
    public Long getManagerGroupId() { return managerGroupId; }
    public void setManagerGroupId(Long managerGroupId) { this.managerGroupId = managerGroupId; }
    public Long getPullerGroupId() { return pullerGroupId; }
    public void setPullerGroupId(Long pullerGroupId) { this.pullerGroupId = pullerGroupId; }
    public Long getStationGroupId() { return stationGroupId; }
    public void setStationGroupId(Long stationGroupId) { this.stationGroupId = stationGroupId; }
    public String getManagerGroupName() { return managerGroupName; }
    public void setManagerGroupName(String managerGroupName) {
        this.managerGroupName = managerGroupName;
    }
    public String getPullerGroupName() { return pullerGroupName; }
    public void setPullerGroupName(String pullerGroupName) {
        this.pullerGroupName = pullerGroupName;
    }
    public String getStationGroupName() { return stationGroupName; }
    public void setStationGroupName(String stationGroupName) {
        this.stationGroupName = stationGroupName;
    }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
