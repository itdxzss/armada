package com.armada.account.contact.model.entity;
/** 账号动态云端受众快照；0 待准备，1 准备中，2 完整，3 失败。 */
public class AccountStatusAudience {
    public static final int NEVER = 0;
    public static final int SYNCING = 1;
    public static final int COMPLETE = 2;
    public static final int FAILED = 3;
    private Long id;
    private Long tenantId;
    private Long accountId;
    private Integer syncStatus;
    private String requestToken;
    private Long leaseUntil;
    private String jidsJson;
    private Integer contactNum;
    private String snapshotVersion;
    private Long syncedAt;
    private Long expiresAt;
    private String failCode;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Integer getSyncStatus() { return syncStatus; }
    public void setSyncStatus(Integer value) { syncStatus = value; }
    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String value) { requestToken = value; }
    public Long getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Long value) { leaseUntil = value; }
    public String getJidsJson() { return jidsJson; }
    public void setJidsJson(String value) { jidsJson = value; }
    public Integer getContactNum() { return contactNum; }
    public void setContactNum(Integer value) { contactNum = value; }
    public String getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(String value) { snapshotVersion = value; }
    public Long getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Long value) { syncedAt = value; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long value) { expiresAt = value; }
    public String getFailCode() { return failCode; }
    public void setFailCode(String value) { failCode = value; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String value) { failReason = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { updatedAt = value; }
}
