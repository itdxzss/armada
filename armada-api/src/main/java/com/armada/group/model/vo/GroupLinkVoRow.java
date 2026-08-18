package com.armada.group.model.vo;

/**
 * Mapper 投影:group_link LEFT JOIN group_link_import_batch,用于分组下链接分页列表。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒,由 Converter 直映出参。
 */
public class GroupLinkVoRow {

    private Long id;
    /** {@code link_url} 列通过 SELECT {@code AS url} 映射到此字段(非 underscore 自动转换)。 */
    private String url;
    private String groupName;
    private String waSubject;
    private String groupJid;
    private String sourceFileName;
    private Integer healthStatus;
    private Boolean banned;
    private Integer memberSize;
    private Integer currentCount;
    private String ownerPhone;
    private String admin;
    /** 历史同步协议来源位掩码:0=未知,1=Web(JSON号),2=Android(六段号),3=两者;不表示当前实时可用协议。 */
    private Integer syncProtocolMask;
    private Integer origin;
    private Integer membershipState;
    private String remark;
    private String avatarUrl;
    private Long lastPreviewAt;
    private Long lastCheckAt;
    private String lastHealthError;
    private Long createdAt;
    private Boolean isHistorical;
    private Boolean isPostControl;
    private Long folderId;
    private String folderName;
    private String inviteUrl;
    private Integer availableAdminCount;
    private String creatorCountryIso2;
    private String creatorCountryName;
    private String creatorCountryFlag;
    private String creatorContinentCode;
    private String creatorPhoneRegionCode;
    private String creatorPhoneRegionName;
    private Long groupCreatedAt;
    private Integer metadataSyncStatus;
    private Long metadataSyncedAt;
    private String metadataSyncError;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getWaSubject() {
        return waSubject;
    }

    public void setWaSubject(String waSubject) {
        this.waSubject = waSubject;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Integer getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(Integer healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Boolean getBanned() {
        return banned;
    }

    public void setBanned(Boolean banned) {
        this.banned = banned;
    }

    public Integer getMemberSize() {
        return memberSize;
    }

    public void setMemberSize(Integer memberSize) {
        this.memberSize = memberSize;
    }

    public Integer getCurrentCount() {
        return currentCount;
    }

    public void setCurrentCount(Integer currentCount) {
        this.currentCount = currentCount;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public void setOwnerPhone(String ownerPhone) {
        this.ownerPhone = ownerPhone;
    }

    public String getAdmin() {
        return admin;
    }

    public void setAdmin(String admin) {
        this.admin = admin;
    }

    public Integer getSyncProtocolMask() {
        return syncProtocolMask;
    }

    public void setSyncProtocolMask(Integer syncProtocolMask) {
        this.syncProtocolMask = syncProtocolMask;
    }

    public Integer getOrigin() {
        return origin;
    }

    public void setOrigin(Integer origin) {
        this.origin = origin;
    }

    public Integer getMembershipState() {
        return membershipState;
    }

    public void setMembershipState(Integer membershipState) {
        this.membershipState = membershipState;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public Long getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(Long lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public String getLastHealthError() {
        return lastHealthError;
    }

    public void setLastHealthError(String lastHealthError) {
        this.lastHealthError = lastHealthError;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getIsHistorical() {
        return isHistorical;
    }

    public void setIsHistorical(Boolean historical) {
        isHistorical = historical;
    }

    public Boolean getIsPostControl() {
        return isPostControl;
    }

    public void setIsPostControl(Boolean postControl) {
        isPostControl = postControl;
    }

    public Long getFolderId() {
        return folderId;
    }

    public void setFolderId(Long folderId) {
        this.folderId = folderId;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public String getInviteUrl() {
        return inviteUrl;
    }

    public void setInviteUrl(String inviteUrl) {
        this.inviteUrl = inviteUrl;
    }

    public Integer getAvailableAdminCount() {
        return availableAdminCount;
    }

    public void setAvailableAdminCount(Integer availableAdminCount) {
        this.availableAdminCount = availableAdminCount;
    }

    public String getCreatorCountryIso2() {
        return creatorCountryIso2;
    }

    public void setCreatorCountryIso2(String creatorCountryIso2) {
        this.creatorCountryIso2 = creatorCountryIso2;
    }

    public String getCreatorCountryName() {
        return creatorCountryName;
    }

    public void setCreatorCountryName(String creatorCountryName) {
        this.creatorCountryName = creatorCountryName;
    }

    public String getCreatorCountryFlag() {
        return creatorCountryFlag;
    }

    public void setCreatorCountryFlag(String creatorCountryFlag) {
        this.creatorCountryFlag = creatorCountryFlag;
    }

    public String getCreatorContinentCode() {
        return creatorContinentCode;
    }

    public void setCreatorContinentCode(String creatorContinentCode) {
        this.creatorContinentCode = creatorContinentCode;
    }

    public String getCreatorPhoneRegionCode() {
        return creatorPhoneRegionCode;
    }

    public void setCreatorPhoneRegionCode(String creatorPhoneRegionCode) {
        this.creatorPhoneRegionCode = creatorPhoneRegionCode;
    }

    public String getCreatorPhoneRegionName() {
        return creatorPhoneRegionName;
    }

    public void setCreatorPhoneRegionName(String creatorPhoneRegionName) {
        this.creatorPhoneRegionName = creatorPhoneRegionName;
    }

    public Long getGroupCreatedAt() {
        return groupCreatedAt;
    }

    public void setGroupCreatedAt(Long groupCreatedAt) {
        this.groupCreatedAt = groupCreatedAt;
    }

    public Integer getMetadataSyncStatus() {
        return metadataSyncStatus;
    }

    public void setMetadataSyncStatus(Integer metadataSyncStatus) {
        this.metadataSyncStatus = metadataSyncStatus;
    }

    public Long getMetadataSyncedAt() {
        return metadataSyncedAt;
    }

    public void setMetadataSyncedAt(Long metadataSyncedAt) {
        this.metadataSyncedAt = metadataSyncedAt;
    }

    public String getMetadataSyncError() {
        return metadataSyncError;
    }

    public void setMetadataSyncError(String metadataSyncError) {
        this.metadataSyncError = metadataSyncError;
    }
}
