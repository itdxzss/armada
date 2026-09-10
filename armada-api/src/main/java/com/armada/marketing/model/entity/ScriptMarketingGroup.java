package com.armada.marketing.model.entity;

/** 剧本营销：ScriptMarketingGroup 持久化事实。 */
public class ScriptMarketingGroup {
    /** 群执行 ID。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 任务 ID。 */
    private Long taskId;
    /** 群链接 ID。 */
    private Long groupLinkId;
    /** 目标群 JID。 */
    private String groupJid;
    /** 目标群名称快照。 */
    private String groupName;
    /** 下一步骤下标，从零开始。 */
    private Integer nextStep;
    /** 下一步骤最早执行时间。 */
    private Long nextAt;
    /** 暂停剩余等待毫秒。 */
    private Long remainingWaitMs;
    /** 启动时固定的角色到账号映射，恢复不重抽。 */
    private String bindingsJson;
    /** 单群暂停，与全任务暂停独立。 */
    private Boolean paused;
    /** 单群暂停原因。 */
    private String pauseReason;
    /** 读取固定映射。 */
    public String getBindingsJson() { return bindingsJson; }
    /** 保存固定映射。 */
    public void setBindingsJson(String value) { bindingsJson = value; }
    /** 读取单群暂停。 */
    public Boolean getPaused() { return paused; }
    /** 设置单群暂停。 */
    public void setPaused(Boolean value) { paused = value; }
    /** 读取原因。 */
    public String getPauseReason() { return pauseReason; }
    /** 设置原因。 */
    public void setPauseReason(String value) { pauseReason = value; }
    /** 读取群执行 ID。 */
    public Long getId() { return id; }
    /** 保存群执行 ID。 */
    public void setId(Long value) { this.id = value; }
    /** 读取租户 ID。 */
    public Long getTenantId() { return tenantId; }
    /** 保存租户 ID。 */
    public void setTenantId(Long value) { this.tenantId = value; }
    /** 读取任务 ID。 */
    public Long getTaskId() { return taskId; }
    /** 保存任务 ID。 */
    public void setTaskId(Long value) { this.taskId = value; }
    /** 读取群链接 ID。 */
    public Long getGroupLinkId() { return groupLinkId; }
    /** 保存群链接 ID。 */
    public void setGroupLinkId(Long value) { this.groupLinkId = value; }
    /** 读取目标群 JID。 */
    public String getGroupJid() { return groupJid; }
    /** 保存目标群 JID。 */
    public void setGroupJid(String value) { this.groupJid = value; }
    /** 读取目标群名称快照。 */
    public String getGroupName() { return groupName; }
    /** 保存目标群名称快照。 */
    public void setGroupName(String value) { this.groupName = value; }
    /** 读取下一步骤下标，从零开始。 */
    public Integer getNextStep() { return nextStep; }
    /** 保存下一步骤下标，从零开始。 */
    public void setNextStep(Integer value) { this.nextStep = value; }
    /** 读取下一步骤最早执行时间。 */
    public Long getNextAt() { return nextAt; }
    /** 保存下一步骤最早执行时间。 */
    public void setNextAt(Long value) { this.nextAt = value; }
    /** 读取暂停剩余等待毫秒。 */
    public Long getRemainingWaitMs() { return remainingWaitMs; }
    /** 保存暂停剩余等待毫秒。 */
    public void setRemainingWaitMs(Long value) { this.remainingWaitMs = value; }
}
