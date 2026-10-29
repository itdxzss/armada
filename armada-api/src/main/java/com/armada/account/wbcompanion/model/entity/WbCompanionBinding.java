package com.armada.account.wbcompanion.model.entity;

/** 安卓号当前挂在 WB 的分身（表 wb_companion_binding），每个账号最多一行。 */
public class WbCompanionBinding {
    /** 主键。 */
    private Long id;
    /** 账号所属租户 ID。 */
    private Long tenantId;
    /** 安卓协议账号 ID。 */
    private Long accountId;
    /** WB 环境标识。 */
    private String wbEnvId;
    /** WB 会员 ID。 */
    private Long wbMemberId;
    /** WB 会员用户名快照。 */
    private String wbUsername;
    /** WB 主设备 ID。 */
    private Long wbDeviceId;
    /** 最近同步时的连接数（主连接 + 影子）。 */
    private Integer connectionCount;
    /** 最近同步时在线的连接数。 */
    private Integer onlineCount;
    /** 最近同步时间（epoch 毫秒）。 */
    private Long syncedAt;
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

    public String getWbEnvId() {
        return wbEnvId;
    }

    public void setWbEnvId(String wbEnvId) {
        this.wbEnvId = wbEnvId;
    }

    public Long getWbMemberId() {
        return wbMemberId;
    }

    public void setWbMemberId(Long wbMemberId) {
        this.wbMemberId = wbMemberId;
    }

    public String getWbUsername() {
        return wbUsername;
    }

    public void setWbUsername(String wbUsername) {
        this.wbUsername = wbUsername;
    }

    public Long getWbDeviceId() {
        return wbDeviceId;
    }

    public void setWbDeviceId(Long wbDeviceId) {
        this.wbDeviceId = wbDeviceId;
    }

    public Integer getConnectionCount() {
        return connectionCount;
    }

    public void setConnectionCount(Integer connectionCount) {
        this.connectionCount = connectionCount;
    }

    public Integer getOnlineCount() {
        return onlineCount;
    }

    public void setOnlineCount(Integer onlineCount) {
        this.onlineCount = onlineCount;
    }

    public Long getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Long syncedAt) {
        this.syncedAt = syncedAt;
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
