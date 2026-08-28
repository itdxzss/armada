package com.armada.account.contact.model.entity;

/** 账号通讯录同步状态，对应 account_contact_sync 表，一账号一行。 */
public class AccountContactSync {

    /** 同步状态：从未同步。 */
    public static final String STATUS_NEVER = "NEVER";
    /** 同步状态：进行中。 */
    public static final String STATUS_SYNCING = "SYNCING";
    /** 同步状态：成功。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 同步状态：失败。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 账号 ID。 */
    private Long accountId;
    /** 最近一次成功同步时间（epoch 毫秒）。 */
    private Long lastSyncedAt;
    /** 最近一次同步触发来源。 */
    private String lastSyncSource;
    /** 最近一次成功同步到的联系人总数。 */
    private Integer contactNum;
    /** 其中通讯录有名字的数量。 */
    private Integer namedNum;
    /** 其中双向好友数量。 */
    private Integer mutualNum;
    /** 同步状态。 */
    private String syncStatus;
    /** 最近一次失败原因。 */
    private String failReason;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Long lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getLastSyncSource() {
        return lastSyncSource;
    }

    public void setLastSyncSource(String lastSyncSource) {
        this.lastSyncSource = lastSyncSource;
    }

    public Integer getContactNum() {
        return contactNum;
    }

    public void setContactNum(Integer contactNum) {
        this.contactNum = contactNum;
    }

    public Integer getNamedNum() {
        return namedNum;
    }

    public void setNamedNum(Integer namedNum) {
        this.namedNum = namedNum;
    }

    public Integer getMutualNum() {
        return mutualNum;
    }

    public void setMutualNum(Integer mutualNum) {
        this.mutualNum = mutualNum;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
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
