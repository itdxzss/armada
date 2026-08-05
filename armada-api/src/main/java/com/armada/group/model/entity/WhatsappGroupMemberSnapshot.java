package com.armada.group.model.entity;

/** WhatsApp 群最后一次完整成员快照实体。 */
public class WhatsappGroupMemberSnapshot {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 群入口 ID。 */
    private Long groupLinkId;

    /** WhatsApp 群 JID。 */
    private String groupJid;

    /** 成员规范化 WhatsApp JID。 */
    private String participantJid;

    /** 协议确认的手机号。 */
    private String phone;

    /** 协议成员角色。 */
    private String role;

    /** 是否管理员或群主。 */
    private Boolean isAdmin;

    /** 是否群主。 */
    private Boolean isOwner;

    /** 快照观察时间(epoch 毫秒)。 */
    private Long snapshotAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

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

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getParticipantJid() {
        return participantJid;
    }

    public void setParticipantJid(String participantJid) {
        this.participantJid = participantJid;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(Boolean admin) {
        isAdmin = admin;
    }

    public Boolean getIsOwner() {
        return isOwner;
    }

    public void setIsOwner(Boolean owner) {
        isOwner = owner;
    }

    public Long getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(Long snapshotAt) {
        this.snapshotAt = snapshotAt;
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
}
