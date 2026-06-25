package com.armada.account.model.entity;

/**
 * 账号生命周期状态子表实体,映射 account_state 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 * step1 导入时一并 INSERT 一行,状态列全留 NULL(未上报),计数列 0。step3 Kafka handler 接上后逐列点亮。
 */
public class AccountState {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入)。 */
    private Long tenantId;

    /** 关联账号 ID(→account.id)。 */
    private Long accountId;

    /** 账号状态:1新增 2正常 3封禁 4导出 5解绑;NULL=未上报。 */
    private Integer accountState;

    /** 登录状态:1在线 2离线;NULL=未上报。 */
    private Integer loginState;

    /** 风控状态:1未风控 2风控中 3待解除;NULL=未上报。 */
    private Integer riskStatus;

    /** 风控倒计时终点(epoch 毫秒)。 */
    private Long riskEndTime;

    /** 冷却到期(epoch 毫秒)。 */
    private Long cooldownUntil;

    /** 禁言状态:1禁言6h 2禁言24h;NULL=未上报。 */
    private Integer muteStatus;

    /** 封号错误码(401/403/440)。 */
    private String blockErrorCode;

    /** 封号原因(落库前按列宽截断)。 */
    private String blockReason;

    /** 状态来源前缀 NEED_REAUTH/PROXY_FAILED(截断)。 */
    private String stateSource;

    /** 最后对账时间(epoch 毫秒)。 */
    private Long lastStateSyncTime;

    /** 失效时间(epoch 毫秒;导出/解绑)。 */
    private Long invalidatedAt;

    /** 真实出口公网 IP(上线探测;≠ip_proxy.host 网关)。 */
    private String truthIp;

    /** 出口国家。 */
    private String proxyCountry;

    /** 代理失败计数。 */
    private Integer proxyFailureCount;

    /** 拉人数量。 */
    private Integer pullIntoGroupCount;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
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

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Integer getLoginState() {
        return loginState;
    }

    public void setLoginState(Integer loginState) {
        this.loginState = loginState;
    }

    public Integer getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(Integer riskStatus) {
        this.riskStatus = riskStatus;
    }

    public Long getRiskEndTime() {
        return riskEndTime;
    }

    public void setRiskEndTime(Long riskEndTime) {
        this.riskEndTime = riskEndTime;
    }

    public Long getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(Long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public Integer getMuteStatus() {
        return muteStatus;
    }

    public void setMuteStatus(Integer muteStatus) {
        this.muteStatus = muteStatus;
    }

    public String getBlockErrorCode() {
        return blockErrorCode;
    }

    public void setBlockErrorCode(String blockErrorCode) {
        this.blockErrorCode = blockErrorCode;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(String blockReason) {
        this.blockReason = blockReason;
    }

    public String getStateSource() {
        return stateSource;
    }

    public void setStateSource(String stateSource) {
        this.stateSource = stateSource;
    }

    public Long getLastStateSyncTime() {
        return lastStateSyncTime;
    }

    public void setLastStateSyncTime(Long lastStateSyncTime) {
        this.lastStateSyncTime = lastStateSyncTime;
    }

    public Long getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Long invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public String getTruthIp() {
        return truthIp;
    }

    public void setTruthIp(String truthIp) {
        this.truthIp = truthIp;
    }

    public String getProxyCountry() {
        return proxyCountry;
    }

    public void setProxyCountry(String proxyCountry) {
        this.proxyCountry = proxyCountry;
    }

    public Integer getProxyFailureCount() {
        return proxyFailureCount;
    }

    public void setProxyFailureCount(Integer proxyFailureCount) {
        this.proxyFailureCount = proxyFailureCount;
    }

    public Integer getPullIntoGroupCount() {
        return pullIntoGroupCount;
    }

    public void setPullIntoGroupCount(Integer pullIntoGroupCount) {
        this.pullIntoGroupCount = pullIntoGroupCount;
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
