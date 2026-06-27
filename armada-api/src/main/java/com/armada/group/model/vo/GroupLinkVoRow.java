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
    private Integer origin;
    private Integer membershipState;
    private String remark;
    private String avatarUrl;
    private Long lastPreviewAt;
    private Long lastCheckAt;
    private String lastHealthError;
    private Long createdAt;

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
}
