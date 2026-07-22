package com.armada.group.model.entity;

/** 账号群关系当前状态实体，映射 account_group_membership。 */
public class AccountGroupMembership {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 账号 ID。 */
    private Long accountId;

    /** 群入口 ID。 */
    private Long groupLinkId;

    /** WhatsApp 群 JID。 */
    private String groupJid;

    /** 该账号是否管理员。 */
    private Boolean admin;

    /** 当前账号群关系状态码。 */
    private Integer membershipStatus;

    /** 当前关系状态来源。 */
    private String statusSource;

    /** 当前关系状态事实时间(epoch毫秒)。 */
    private Long statusUpdatedAt;

    /** 账号上控后首次探测到进入该群的时间(epoch毫秒)。 */
    private Long joinedAt;

    /** 最近看到该关系的时间(epoch毫秒)。 */
    private Long lastSeenAt;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
    private Long updatedAt;

    /** 旧重复历史或真正废弃记录的软删时间(epoch毫秒)。 */
    private Long deletedAt;

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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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

    public Boolean getAdmin() {
        return admin;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public Integer getMembershipStatus() {
        return membershipStatus;
    }

    public void setMembershipStatus(Integer membershipStatus) {
        this.membershipStatus = membershipStatus;
    }

    public String getStatusSource() {
        return statusSource;
    }

    public void setStatusSource(String statusSource) {
        this.statusSource = statusSource;
    }

    public Long getStatusUpdatedAt() {
        return statusUpdatedAt;
    }

    public void setStatusUpdatedAt(Long statusUpdatedAt) {
        this.statusUpdatedAt = statusUpdatedAt;
    }

    public Long getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
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

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
