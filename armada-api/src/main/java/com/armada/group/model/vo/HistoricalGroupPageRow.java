package com.armada.group.model.vo;

/** 账号组历史群 SQL 聚合行。 */
public class HistoricalGroupPageRow {

    private String groupJid;
    private String subject;
    private String inviteCode;
    private String ownerPhone;
    private Long groupCreatedAt;
    private Integer knownMembershipCount;
    private Integer inGroupCount;
    private Boolean adminInGroup;
    private Boolean ownerInGroup;
    private Boolean announceOnly;
    private Integer memberSize;
    private Boolean operable;

    /** @return WhatsApp 群 JID */
    public String getGroupJid() { return groupJid; }
    /** @param groupJid WhatsApp 群 JID */
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    /** @return 群名称 */
    public String getSubject() { return subject; }
    /** @param subject 群名称 */
    public void setSubject(String subject) { this.subject = subject; }
    /** @return 群邀请链接码 */
    public String getInviteCode() { return inviteCode; }
    /** @param inviteCode 群邀请链接码 */
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    /** @return 群主号码 */
    public String getOwnerPhone() { return ownerPhone; }
    /** @param ownerPhone 群主号码 */
    public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
    /** @return WhatsApp 群创建时间,Unix 秒 */
    public Long getGroupCreatedAt() { return groupCreatedAt; }
    /** @param groupCreatedAt WhatsApp 群创建时间,Unix 秒 */
    public void setGroupCreatedAt(Long groupCreatedAt) { this.groupCreatedAt = groupCreatedAt; }
    /** @return 已确认关系数量 */
    public Integer getKnownMembershipCount() { return knownMembershipCount; }
    /** @param knownMembershipCount 已确认关系数量 */
    public void setKnownMembershipCount(Integer knownMembershipCount) {
        this.knownMembershipCount = knownMembershipCount;
    }
    /** @return 当前真实在群账号数量 */
    public Integer getInGroupCount() { return inGroupCount; }
    /** @param inGroupCount 当前真实在群账号数量 */
    public void setInGroupCount(Integer inGroupCount) { this.inGroupCount = inGroupCount; }
    /** @return 是否存在当前在群管理员 */
    public Boolean getAdminInGroup() { return adminInGroup; }
    /** @param adminInGroup 是否存在当前在群管理员 */
    public void setAdminInGroup(Boolean adminInGroup) { this.adminInGroup = adminInGroup; }
    /** @return 是否存在当前在群群主 */
    public Boolean getOwnerInGroup() { return ownerInGroup; }
    /** @param ownerInGroup 是否存在当前在群群主 */
    public void setOwnerInGroup(Boolean ownerInGroup) { this.ownerInGroup = ownerInGroup; }
    /** @return 是否仅管理员可发言 */
    public Boolean getAnnounceOnly() { return announceOnly; }
    /** @param announceOnly 是否仅管理员可发言 */
    public void setAnnounceOnly(Boolean announceOnly) { this.announceOnly = announceOnly; }
    /** @return 群成员数量 */
    public Integer getMemberSize() { return memberSize; }
    /** @param memberSize 群成员数量 */
    public void setMemberSize(Integer memberSize) { this.memberSize = memberSize; }
    /** @return 是否存在当前在线可执行管理员 */
    public Boolean getOperable() { return operable; }
    /** @param operable 是否存在当前在线可执行管理员 */
    public void setOperable(Boolean operable) { this.operable = operable; }
}
