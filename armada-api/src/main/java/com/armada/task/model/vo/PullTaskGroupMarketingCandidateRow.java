package com.armada.task.model.vo;

/** 拉群营销候选群组 SQL 聚合行。 */
public class PullTaskGroupMarketingCandidateRow {

    /** 群链接主键。 */
    private Long groupLinkId;
    /** 群入口归属用户。 */
    private Long ownerUserId;
    /** WhatsApp 群唯一 JID。 */
    private String groupJid;
    /** 当前群名称。 */
    private String groupName;
    /** 群主手机号。 */
    private String ownerPhone;
    /** WhatsApp 群创建时间(Unix 秒)。 */
    private Long groupCreatedAt;
    /** 当前群人数。 */
    private Integer memberSize;
    /** 是否仅管理员可发言。 */
    private Boolean announceOnly;
    /** 群头像地址。 */
    private String avatarUrl;
    /** 群资料、健康或关系最近同步时间。 */
    private Long lastSyncedAt;
    /** 群健康状态。 */
    private Integer healthStatus;
    /** 群是否已封禁。 */
    private Boolean banned;
    /** 最近健康检查错误。 */
    private String healthError;
    /** 是否属于历史群来源。 */
    private Boolean historical;
    /** 是否属于自收群来源。 */
    private Boolean selfCollected;
    /** 当前群内创建者或管理员关系数量。 */
    private Integer adminRelationCount;
    /** 当前可用管理账号数量。 */
    private Integer eligibleAccountCount;
    /** 当前在线可用管理账号数量。 */
    private Integer onlineAccountCount;
    /** 建立自收群来源的进群任务 ID。 */
    private Long sourceJoinTaskId;
    /** 建立自收群来源的进群任务名称。 */
    private String sourceJoinTaskName;
    /** 来源账号进群时间。 */
    private Long sourceJoinedAt;
    /** 来源账号成为管理员时间。 */
    private Long sourcePromotedAt;
    /** 当前占用类型。 */
    private String occupancyType;
    /** 当前等待池标识。 */
    private String reservationToken;
    /** 当前硬占用任务 ID。 */
    private Long occupiedTaskId;
    /** 当前占用任务名称。 */
    private String occupiedTaskName;
    /** 当前占用创建人。 */
    private Long occupiedBy;
    /** 最近占用校验时间。 */
    private Long lastValidatedAt;
    /** 最近占用校验失败原因。 */
    private String lastValidationReason;

    public Long getGroupLinkId() { return groupLinkId; }
    public void setGroupLinkId(Long groupLinkId) { this.groupLinkId = groupLinkId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getOwnerPhone() { return ownerPhone; }
    public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
    public Long getGroupCreatedAt() { return groupCreatedAt; }
    public void setGroupCreatedAt(Long groupCreatedAt) { this.groupCreatedAt = groupCreatedAt; }
    public Integer getMemberSize() { return memberSize; }
    public void setMemberSize(Integer memberSize) { this.memberSize = memberSize; }
    public Boolean getAnnounceOnly() { return announceOnly; }
    public void setAnnounceOnly(Boolean announceOnly) { this.announceOnly = announceOnly; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Long getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Long lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public Integer getHealthStatus() { return healthStatus; }
    public void setHealthStatus(Integer healthStatus) { this.healthStatus = healthStatus; }
    public Boolean getBanned() { return banned; }
    public void setBanned(Boolean banned) { this.banned = banned; }
    public String getHealthError() { return healthError; }
    public void setHealthError(String healthError) { this.healthError = healthError; }
    public Boolean getHistorical() { return historical; }
    public void setHistorical(Boolean historical) { this.historical = historical; }
    public Boolean getSelfCollected() { return selfCollected; }
    public void setSelfCollected(Boolean selfCollected) { this.selfCollected = selfCollected; }
    public Integer getAdminRelationCount() { return adminRelationCount; }
    public void setAdminRelationCount(Integer adminRelationCount) { this.adminRelationCount = adminRelationCount; }
    public Integer getEligibleAccountCount() { return eligibleAccountCount; }
    public void setEligibleAccountCount(Integer eligibleAccountCount) {
        this.eligibleAccountCount = eligibleAccountCount;
    }
    public Integer getOnlineAccountCount() { return onlineAccountCount; }
    public void setOnlineAccountCount(Integer onlineAccountCount) { this.onlineAccountCount = onlineAccountCount; }
    public Long getSourceJoinTaskId() { return sourceJoinTaskId; }
    public void setSourceJoinTaskId(Long sourceJoinTaskId) { this.sourceJoinTaskId = sourceJoinTaskId; }
    public String getSourceJoinTaskName() { return sourceJoinTaskName; }
    public void setSourceJoinTaskName(String sourceJoinTaskName) { this.sourceJoinTaskName = sourceJoinTaskName; }
    public Long getSourceJoinedAt() { return sourceJoinedAt; }
    public void setSourceJoinedAt(Long sourceJoinedAt) { this.sourceJoinedAt = sourceJoinedAt; }
    public Long getSourcePromotedAt() { return sourcePromotedAt; }
    public void setSourcePromotedAt(Long sourcePromotedAt) { this.sourcePromotedAt = sourcePromotedAt; }
    public String getOccupancyType() { return occupancyType; }
    public void setOccupancyType(String occupancyType) { this.occupancyType = occupancyType; }
    public String getReservationToken() { return reservationToken; }
    public void setReservationToken(String reservationToken) { this.reservationToken = reservationToken; }
    public Long getOccupiedTaskId() { return occupiedTaskId; }
    public void setOccupiedTaskId(Long occupiedTaskId) { this.occupiedTaskId = occupiedTaskId; }
    public String getOccupiedTaskName() { return occupiedTaskName; }
    public void setOccupiedTaskName(String occupiedTaskName) { this.occupiedTaskName = occupiedTaskName; }
    public Long getOccupiedBy() { return occupiedBy; }
    public void setOccupiedBy(Long occupiedBy) { this.occupiedBy = occupiedBy; }
    public Long getLastValidatedAt() { return lastValidatedAt; }
    public void setLastValidatedAt(Long lastValidatedAt) { this.lastValidatedAt = lastValidatedAt; }
    public String getLastValidationReason() { return lastValidationReason; }
    public void setLastValidationReason(String lastValidationReason) {
        this.lastValidationReason = lastValidationReason;
    }
}
