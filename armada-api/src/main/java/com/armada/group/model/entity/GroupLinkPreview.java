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
    private Long inviteCodeObservedAt;

    /** 协议层返回的 WhatsApp 真实群名称。 */
    private String waSubject;

    /** WhatsApp 群描述。 */
    private String waDescription;

    /** 本次响应是否明确观察到群描述；仅供 Mapper 三态更新。 */
    private Boolean waDescriptionObserved;

    /** 预览时刻返回的群成员数量。 */
    private Integer memberSize;

    /** 群主号码。 */
    private String ownerPhone;

    /** 本次响应是否明确观察到群主身份；仅供 Mapper 三态更新，不对应数据库列。 */
    private Boolean ownerPhoneObserved;

    /** 是否仅管理员可发言:NULL=未知 0=否 1=是。 */
    private Boolean announceOnly;

    /** 本次响应是否明确观察到发言权限；仅供 Mapper 三态更新。 */
    private Boolean announceOnlyObserved;

    /** 是否仅管理员可编辑群资料。 */
    private Boolean adminOnlyEditInfo;

    /** 本次响应是否明确观察到编辑权限；仅供 Mapper 三态更新。 */
    private Boolean adminOnlyEditInfoObserved;

    /** 是否允许普通成员添加成员。 */
    private Boolean memberAddMode;

    /** 本次响应是否明确观察到添加成员权限；仅供 Mapper 三态更新。 */
    private Boolean memberAddModeObserved;

    /** 是否开启新成员入群审批。 */
    private Boolean joinApprovalMode;

    /** 本次响应是否明确观察到入群审批；仅供 Mapper 三态更新。 */
    private Boolean joinApprovalModeObserved;

    /** 限时消息保留秒数；0 表示关闭。 */
    private Integer ephemeralDurationSeconds;

    /** 本次响应是否明确观察到限时消息设置；仅供 Mapper 三态更新。 */
    private Boolean ephemeralDurationObserved;

    /** WhatsApp 群创建时间(Unix 秒)。 */
    private Long groupCreatedAt;

    /** 严格按群主确认手机号解析的国家 ISO2。 */
    private String creatorCountryIso2;

    /** 严格按群主确认手机号解析的大洲代码。 */
    private String creatorContinentCode;

    /** 本次响应是否明确观察到可用于解析地区的群主身份。 */
    private Boolean creatorCountryObserved;

    /** 群头像 URL。 */
    private String avatarUrl;

    /** 最近一次预览/解析成功时间(epoch毫秒)。 */
    private Long lastPreviewAt;

    /** 群详情请求开始观察时间(epoch毫秒)。 */
    private Long metadataObservedAt;

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

    public Long getInviteCodeObservedAt() {
        return inviteCodeObservedAt;
    }

    public void setInviteCodeObservedAt(Long inviteCodeObservedAt) {
        this.inviteCodeObservedAt = inviteCodeObservedAt;
    }

    public String getWaSubject() {
        return waSubject;
    }

    public void setWaSubject(String waSubject) {
        this.waSubject = waSubject;
    }

    public String getWaDescription() {
        return waDescription;
    }

    public void setWaDescription(String waDescription) {
        this.waDescription = waDescription;
    }

    public Boolean getWaDescriptionObserved() {
        return waDescriptionObserved;
    }

    public void setWaDescriptionObserved(Boolean waDescriptionObserved) {
        this.waDescriptionObserved = waDescriptionObserved;
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

    public Boolean getOwnerPhoneObserved() {
        return ownerPhoneObserved;
    }

    public void setOwnerPhoneObserved(Boolean ownerPhoneObserved) {
        this.ownerPhoneObserved = ownerPhoneObserved;
    }

    public Boolean getAnnounceOnly() {
        return announceOnly;
    }

    public void setAnnounceOnly(Boolean announceOnly) {
        this.announceOnly = announceOnly;
    }

    public Boolean getAnnounceOnlyObserved() {
        return announceOnlyObserved;
    }

    public void setAnnounceOnlyObserved(Boolean announceOnlyObserved) {
        this.announceOnlyObserved = announceOnlyObserved;
    }

    public Boolean getAdminOnlyEditInfo() {
        return adminOnlyEditInfo;
    }

    public void setAdminOnlyEditInfo(Boolean adminOnlyEditInfo) {
        this.adminOnlyEditInfo = adminOnlyEditInfo;
    }

    public Boolean getAdminOnlyEditInfoObserved() {
        return adminOnlyEditInfoObserved;
    }

    public void setAdminOnlyEditInfoObserved(Boolean adminOnlyEditInfoObserved) {
        this.adminOnlyEditInfoObserved = adminOnlyEditInfoObserved;
    }

    public Boolean getMemberAddMode() {
        return memberAddMode;
    }

    public void setMemberAddMode(Boolean memberAddMode) {
        this.memberAddMode = memberAddMode;
    }

    public Boolean getMemberAddModeObserved() {
        return memberAddModeObserved;
    }

    public void setMemberAddModeObserved(Boolean memberAddModeObserved) {
        this.memberAddModeObserved = memberAddModeObserved;
    }

    public Boolean getJoinApprovalMode() {
        return joinApprovalMode;
    }

    public void setJoinApprovalMode(Boolean joinApprovalMode) {
        this.joinApprovalMode = joinApprovalMode;
    }

    public Boolean getJoinApprovalModeObserved() {
        return joinApprovalModeObserved;
    }

    public void setJoinApprovalModeObserved(Boolean joinApprovalModeObserved) {
        this.joinApprovalModeObserved = joinApprovalModeObserved;
    }

    public Integer getEphemeralDurationSeconds() {
        return ephemeralDurationSeconds;
    }

    public void setEphemeralDurationSeconds(Integer ephemeralDurationSeconds) {
        this.ephemeralDurationSeconds = ephemeralDurationSeconds;
    }

    public Boolean getEphemeralDurationObserved() {
        return ephemeralDurationObserved;
    }

    public void setEphemeralDurationObserved(Boolean ephemeralDurationObserved) {
        this.ephemeralDurationObserved = ephemeralDurationObserved;
    }

    public Long getGroupCreatedAt() {
        return groupCreatedAt;
    }

    public void setGroupCreatedAt(Long groupCreatedAt) {
        this.groupCreatedAt = groupCreatedAt;
    }

    public String getCreatorCountryIso2() {
        return creatorCountryIso2;
    }

    public void setCreatorCountryIso2(String creatorCountryIso2) {
        this.creatorCountryIso2 = creatorCountryIso2;
    }

    public String getCreatorContinentCode() {
        return creatorContinentCode;
    }

    public void setCreatorContinentCode(String creatorContinentCode) {
        this.creatorContinentCode = creatorContinentCode;
    }

    public Boolean getCreatorCountryObserved() {
        return creatorCountryObserved;
    }

    public void setCreatorCountryObserved(Boolean creatorCountryObserved) {
        this.creatorCountryObserved = creatorCountryObserved;
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

    public Long getMetadataObservedAt() {
        return metadataObservedAt;
    }

    public void setMetadataObservedAt(Long metadataObservedAt) {
        this.metadataObservedAt = metadataObservedAt;
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
