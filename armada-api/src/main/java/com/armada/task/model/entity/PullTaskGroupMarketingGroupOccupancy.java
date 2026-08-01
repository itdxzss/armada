package com.armada.task.model.entity;

/** 拉群营销群组软占用/硬占用实体。 */
public class PullTaskGroupMarketingGroupOccupancy {

    /** 主键。 */
    private Long id;
    /** 所属租户 ID。 */
    private Long tenantId;
    /** 关联群链接 ID。 */
    private Long groupLinkId;
    /** WhatsApp 群唯一 JID。 */
    private String groupJid;
    /** 群来源：HISTORICAL 历史群或 SELF_COLLECTED 自收群。 */
    private String groupSource;
    /** WAITING 等待池软占用或 HARD_LOCK 任务硬锁。 */
    private String occupancyType;
    /** 创建任务前的等待池随机标识。 */
    private String reservationToken;
    /** 硬锁关联任务 ID。 */
    private Long taskId;
    /** 任务名称展示快照。 */
    private String taskNameSnapshot;
    /** 计划启动时间(epoch 毫秒)。 */
    private Long plannedStartAt;
    /** 最近一次校验失败原因。 */
    private String lastValidationReason;
    /** 最近一次校验时间(epoch 毫秒)。 */
    private Long lastValidatedAt;
    /** 占用创建人。 */
    private Long createdBy;
    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;
    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
    /** 等待池软占用过期时间(epoch 毫秒)。 */
    private Long expiresAt;
    /** 释放时间(epoch 毫秒)。 */
    private Long releasedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getGroupLinkId() { return groupLinkId; }
    public void setGroupLinkId(Long groupLinkId) { this.groupLinkId = groupLinkId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public String getGroupSource() { return groupSource; }
    public void setGroupSource(String groupSource) { this.groupSource = groupSource; }
    public String getOccupancyType() { return occupancyType; }
    public void setOccupancyType(String occupancyType) { this.occupancyType = occupancyType; }
    public String getReservationToken() { return reservationToken; }
    public void setReservationToken(String reservationToken) { this.reservationToken = reservationToken; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskNameSnapshot() { return taskNameSnapshot; }
    public void setTaskNameSnapshot(String taskNameSnapshot) { this.taskNameSnapshot = taskNameSnapshot; }
    public Long getPlannedStartAt() { return plannedStartAt; }
    public void setPlannedStartAt(Long plannedStartAt) { this.plannedStartAt = plannedStartAt; }
    public String getLastValidationReason() { return lastValidationReason; }
    public void setLastValidationReason(String lastValidationReason) {
        this.lastValidationReason = lastValidationReason;
    }
    public Long getLastValidatedAt() { return lastValidatedAt; }
    public void setLastValidatedAt(Long lastValidatedAt) { this.lastValidatedAt = lastValidatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public Long getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Long releasedAt) { this.releasedAt = releasedAt; }
}
