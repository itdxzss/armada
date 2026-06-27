package com.armada.group.model.entity;

/** 群链接协议预览元数据实体,映射 group_link_preview。 */
public class GroupLinkPreview {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 关联 group_link.id。 */
    private Long groupLinkId;

    /** WhatsApp 群 JID,协议层操作群的真实标识。 */
    private String groupJid;

    /** 群邀请链接里的邀请码 code。 */
    private String inviteCode;

    /** 协议层返回的 WhatsApp 真实群名称。 */
    private String waSubject;

    /** 预览时刻返回的群成员数量。 */
    private Integer memberSize;

    /** 群主号码。 */
    private String ownerPhone;

    /** 是否仅管理员可发言:NULL=未知 0=否 1=是。 */
    private Boolean announceOnly;

    /** 群头像 URL。 */
    private String avatarUrl;

    /** 最近一次预览/解析成功时间(epoch毫秒)。 */
    private Long lastPreviewAt;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
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

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public String getWaSubject() {
        return waSubject;
    }

    public void setWaSubject(String waSubject) {
        this.waSubject = waSubject;
    }

    public Integer getMemberSize() {
        return memberSize;
    }

    public void setMemberSize(Integer memberSize) {
        this.memberSize = memberSize;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public void setOwnerPhone(String ownerPhone) {
        this.ownerPhone = ownerPhone;
    }

    public Boolean getAnnounceOnly() {
        return announceOnly;
    }

    public void setAnnounceOnly(Boolean announceOnly) {
        this.announceOnly = announceOnly;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Long getLastPreviewAt() {
        return lastPreviewAt;
    }

    public void setLastPreviewAt(Long lastPreviewAt) {
        this.lastPreviewAt = lastPreviewAt;
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
