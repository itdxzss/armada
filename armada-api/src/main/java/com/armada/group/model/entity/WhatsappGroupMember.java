package com.armada.group.model.entity;

/** WhatsApp 群成员最新关系事实，独立于 Armada 受控账号。 */
public class WhatsappGroupMember {

    private Long id;
    private Long tenantId;
    private Long groupLinkId;
    private String groupJid;
    private String memberJid;
    private String participantJid;
    private String phone;
    private String role;
    private Boolean admin;
    private Boolean owner;
    private Integer membershipStatus;
    private String statusSource;
    private String statusSourceEventId;
    private Long statusUpdatedAt;
    private Long joinedAt;
    private Integer lastExitType;
    private Long lastExitedAt;
    private Long firstSeenAt;
    private Long lastSeenAt;
    private Long observerAccountId;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getGroupLinkId() { return groupLinkId; }
    public void setGroupLinkId(Long groupLinkId) { this.groupLinkId = groupLinkId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public String getMemberJid() { return memberJid; }
    public void setMemberJid(String memberJid) { this.memberJid = memberJid; }
    public String getParticipantJid() { return participantJid; }
    public void setParticipantJid(String participantJid) { this.participantJid = participantJid; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Boolean getAdmin() { return admin; }
    public void setAdmin(Boolean admin) { this.admin = admin; }
    public Boolean getOwner() { return owner; }
    public void setOwner(Boolean owner) { this.owner = owner; }
    public Integer getMembershipStatus() { return membershipStatus; }
    public void setMembershipStatus(Integer membershipStatus) { this.membershipStatus = membershipStatus; }
    public String getStatusSource() { return statusSource; }
    public void setStatusSource(String statusSource) { this.statusSource = statusSource; }
    public String getStatusSourceEventId() { return statusSourceEventId; }
    public void setStatusSourceEventId(String statusSourceEventId) { this.statusSourceEventId = statusSourceEventId; }
    public Long getStatusUpdatedAt() { return statusUpdatedAt; }
    public void setStatusUpdatedAt(Long statusUpdatedAt) { this.statusUpdatedAt = statusUpdatedAt; }
    public Long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Long joinedAt) { this.joinedAt = joinedAt; }
    public Integer getLastExitType() { return lastExitType; }
    public void setLastExitType(Integer lastExitType) { this.lastExitType = lastExitType; }
    public Long getLastExitedAt() { return lastExitedAt; }
    public void setLastExitedAt(Long lastExitedAt) { this.lastExitedAt = lastExitedAt; }
    public Long getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Long firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Long lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Long getObserverAccountId() { return observerAccountId; }
    public void setObserverAccountId(Long observerAccountId) { this.observerAccountId = observerAccountId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
