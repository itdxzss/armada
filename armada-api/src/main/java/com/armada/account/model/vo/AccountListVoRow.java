package com.armada.account.model.vo;

/**
 * Mapper 投影:account LEFT JOIN account_state LEFT JOIN account_group,用于账号分页列表。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒(UTC)。状态列来自 account_state(LEFT JOIN),step1 导入后全为 NULL。
 */
public class AccountListVoRow {

    // ---- account 主表真值列 ----

    /** 账号主键。 */
    private Long id;

    /** WA 号。 */
    private String wsPhone;

    /** 账号类型:1个人 2商业。铁律:导入即冻结不可改写。 */
    private Integer accountType;

    /** 机型:1安卓 2苹果。 */
    private Integer deviceOs;

    /** 来源:1买量 2裂变 3自购。 */
    private String numberSource;

    /** 推广渠道名。 */
    private String channelName;

    /** 接入协议标识。 */
    private String protocolId;

    /** 归属分组 ID(→account_group.id)。 */
    private Long accountGroupId;

    /** 分组名称(LEFT JOIN account_group,分组软删时为 null)。 */
    private String groupName;

    /** 归属:1自有 2平台 3租借。 */
    private Integer ownership;

    /** 租借到期(epoch 毫秒;ownership=3)。 */
    private Long leaseUntil;

    /** 首次派单时间(epoch 毫秒;未分配时为 null)。 */
    private Long dispatchedAt;

    /** 入库时间(epoch 毫秒)。 */
    private Long createdAt;

    // ---- account_state 状态列(LEFT JOIN,全可空) ----

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

    /** 封号原因。 */
    private String blockReason;

    /** 状态来源前缀(NEED_REAUTH/PROXY_FAILED)。 */
    private String stateSource;

    /** 真实出口公网 IP。 */
    private String truthIp;

    /** 出口国家。 */
    private String proxyCountry;

    /** 拉人数量。 */
    private Integer pullIntoGroupCount;

    /** 失效时间(epoch 毫秒;导出/解绑)。 */
    private Long invalidatedAt;

    /** 最后对账时间(epoch 毫秒)。 */
    private Long lastStateSyncTime;

    // ---- getters / setters ----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public Integer getAccountType() {
        return accountType;
    }

    public void setAccountType(Integer accountType) {
        this.accountType = accountType;
    }

    public Integer getDeviceOs() {
        return deviceOs;
    }

    public void setDeviceOs(Integer deviceOs) {
        this.deviceOs = deviceOs;
    }

    public String getNumberSource() {
        return numberSource;
    }

    public void setNumberSource(String numberSource) {
        this.numberSource = numberSource;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public void setProtocolId(String protocolId) {
        this.protocolId = protocolId;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Integer getOwnership() {
        return ownership;
    }

    public void setOwnership(Integer ownership) {
        this.ownership = ownership;
    }

    public Long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Long getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Long dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
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

    public Integer getPullIntoGroupCount() {
        return pullIntoGroupCount;
    }

    public void setPullIntoGroupCount(Integer pullIntoGroupCount) {
        this.pullIntoGroupCount = pullIntoGroupCount;
    }

    public Long getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Long invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public Long getLastStateSyncTime() {
        return lastStateSyncTime;
    }

    public void setLastStateSyncTime(Long lastStateSyncTime) {
        this.lastStateSyncTime = lastStateSyncTime;
    }
}
