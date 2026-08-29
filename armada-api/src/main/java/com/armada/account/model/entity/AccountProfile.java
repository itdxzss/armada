package com.armada.account.model.entity;

/**
 * 账号营销筛选画像实体，对应 account_profile 一行。
 *
 * <p>画像事实和账号身份分开保存。好友数、拉群权限、轮号和营销来源各用独立水位防止乱序覆盖；
 * 任一业务字段为 {@code null} 均表示尚未获得可信事实。</p>
 */
public class AccountProfile {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 关联账号 ID。 */
    private Long accountId;

    /** 通讯录双向好友数；NULL=未采集。 */
    private Integer friendCount;

    /** 好友数最近同步时间(epoch 毫秒)。 */
    private Long friendCountSyncedAt;

    /** 是否允许被拉群；NULL=未采集。 */
    private Boolean groupInviteAllowed;

    /** 拉群隐私最近同步时间(epoch 毫秒)。 */
    private Long groupInviteSyncedAt;

    /** 轮号状态：0未轮号、1轮号中、2成功、3失败；NULL=未知。 */
    private Integer rotationStatus;

    /** 轮号状态最近更新时间(epoch 毫秒)。 */
    private Long rotationUpdatedAt;

    /** WhatsApp 估算注册时间(epoch 毫秒)；NULL=未知。 */
    private Long registeredAt;

    /** 注册时间来源：1供应商准确日期、2供应商号龄反推、3人工维护。 */
    private Integer registeredAtSource;

    /** 运营来源：0买量、1自登、2买入、3转入、4群扫码；NULL=未知。 */
    private Integer marketingSource;

    /** 运营来源最近更新时间(epoch 毫秒)。 */
    private Long marketingSourceUpdatedAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 最近落库时间(epoch 毫秒)。 */
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

    public Integer getFriendCount() {
        return friendCount;
    }

    public void setFriendCount(Integer friendCount) {
        this.friendCount = friendCount;
    }

    public Long getFriendCountSyncedAt() {
        return friendCountSyncedAt;
    }

    public void setFriendCountSyncedAt(Long friendCountSyncedAt) {
        this.friendCountSyncedAt = friendCountSyncedAt;
    }

    public Boolean getGroupInviteAllowed() {
        return groupInviteAllowed;
    }

    public void setGroupInviteAllowed(Boolean groupInviteAllowed) {
        this.groupInviteAllowed = groupInviteAllowed;
    }

    public Long getGroupInviteSyncedAt() {
        return groupInviteSyncedAt;
    }

    public void setGroupInviteSyncedAt(Long groupInviteSyncedAt) {
        this.groupInviteSyncedAt = groupInviteSyncedAt;
    }

    public Integer getRotationStatus() {
        return rotationStatus;
    }

    public void setRotationStatus(Integer rotationStatus) {
        this.rotationStatus = rotationStatus;
    }

    public Long getRotationUpdatedAt() {
        return rotationUpdatedAt;
    }

    public void setRotationUpdatedAt(Long rotationUpdatedAt) {
        this.rotationUpdatedAt = rotationUpdatedAt;
    }

    public Long getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Long registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Integer getRegisteredAtSource() {
        return registeredAtSource;
    }

    public void setRegisteredAtSource(Integer registeredAtSource) {
        this.registeredAtSource = registeredAtSource;
    }

    public Integer getMarketingSource() {
        return marketingSource;
    }

    public void setMarketingSource(Integer marketingSource) {
        this.marketingSource = marketingSource;
    }

    public Long getMarketingSourceUpdatedAt() {
        return marketingSourceUpdatedAt;
    }

    public void setMarketingSourceUpdatedAt(Long marketingSourceUpdatedAt) {
        this.marketingSourceUpdatedAt = marketingSourceUpdatedAt;
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
