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

    /** 当前有效账号类型:1个人 2商业。 */
    private Integer accountType;

    /** 导入申报账号类型:1个人 2商业。 */
    private Integer declaredAccountType;

    /** 协议校验状态:0待校验 1已匹配 2已纠正 3无法确认 4存量未校验。 */
    private Integer accountTypeVerifyStatus;

    /** 协议校验来源:1凭据元数据 2配对结果 3商业资料查询。 */
    private Integer accountTypeVerifySource;

    /** 账号类型最后校验时间(epoch 毫秒)。 */
    private Long accountTypeVerifiedAt;

    /** 商业认证级别:1蓝标高认证 2明确非高认证；null 未确认。 */
    private Integer businessVerificationLevel;

    /** 商业认证识别来源。 */
    private Integer businessVerificationSource;

    /** 商业认证级别最后确认时间(epoch 毫秒)。 */
    private Long businessVerificationVerifiedAt;

    /** 机型:1安卓 2苹果。 */
    private Integer deviceOs;

    /** 来源:1买量 2裂变 3自购。 */
    private Integer numberSource;

    /** 推广渠道名。 */
    private String channelName;

    /** 接入协议标识。 */
    private String protocolId;

    /** 协议后端:WEB/ANDROID。 */
    private String protocolBackend;

    /** 归属分组 ID(→account_group.id)。 */
    private Long accountGroupId;

    /** 分组名称(LEFT JOIN account_group,分组软删时为 null)。 */
    private String groupName;

    /** 分组持久化营销占用类型；为空表示分组空闲。 */
    private Integer marketingOccupancyType;

    /** 当前占用营销任务 ID；为空表示分组空闲。 */
    private Long marketingOccupancyTaskId;

    /** 当前营销分组锁定时间(epoch 毫秒)。 */
    private Long marketingLockedAt;

    /** 归属:1自有 2平台 3租借。 */
    private Integer ownership;

    /** 租借到期(epoch 毫秒;ownership=3)。 */
    private Long leaseUntil;

    /** 首次派单时间(epoch 毫秒;未分配时为 null)。 */
    private Long dispatchedAt;

    /** 入库时间(epoch 毫秒)。 */
    private Long createdAt;

    // ---- account_state 状态列(LEFT JOIN,全可空) ----

    /** 账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限;NULL=未上报。 */
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

    /** 出口国家对应的国旗 emoji;混合国家或无匹配国家时为空。 */
    private String countryFlag;

    /** 代理来源展示快照;为空时来自当前绑定代理。 */
    private String ipSource;

    /** 拉人数量。 */
    private Integer pullIntoGroupCount;

    /** 失效时间(epoch 毫秒;账号状态非正常;恢复正常清空)。 */
    private Long invalidatedAt;

    /** 最后对账时间(epoch 毫秒)。 */
    private Long lastStateSyncTime;

    /** 上控后当前有效群组数。 */
    private int groupsNum;

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

    public Integer getDeclaredAccountType() {
        return declaredAccountType;
    }

    public void setDeclaredAccountType(Integer declaredAccountType) {
        this.declaredAccountType = declaredAccountType;
    }

    public Integer getAccountTypeVerifyStatus() {
        return accountTypeVerifyStatus;
    }

    public void setAccountTypeVerifyStatus(Integer accountTypeVerifyStatus) {
        this.accountTypeVerifyStatus = accountTypeVerifyStatus;
    }

    public Integer getAccountTypeVerifySource() {
        return accountTypeVerifySource;
    }

    public void setAccountTypeVerifySource(Integer accountTypeVerifySource) {
        this.accountTypeVerifySource = accountTypeVerifySource;
    }

    public Long getAccountTypeVerifiedAt() {
        return accountTypeVerifiedAt;
    }

    public void setAccountTypeVerifiedAt(Long accountTypeVerifiedAt) {
        this.accountTypeVerifiedAt = accountTypeVerifiedAt;
    }

    public Integer getBusinessVerificationLevel() {
        return businessVerificationLevel;
    }

    public void setBusinessVerificationLevel(Integer businessVerificationLevel) {
        this.businessVerificationLevel = businessVerificationLevel;
    }

    public Integer getBusinessVerificationSource() {
        return businessVerificationSource;
    }

    public void setBusinessVerificationSource(Integer businessVerificationSource) {
        this.businessVerificationSource = businessVerificationSource;
    }

    public Long getBusinessVerificationVerifiedAt() {
        return businessVerificationVerifiedAt;
    }

    public void setBusinessVerificationVerifiedAt(Long businessVerificationVerifiedAt) {
        this.businessVerificationVerifiedAt = businessVerificationVerifiedAt;
    }

    public Integer getDeviceOs() {
        return deviceOs;
    }

    public void setDeviceOs(Integer deviceOs) {
        this.deviceOs = deviceOs;
    }

    public Integer getNumberSource() {
        return numberSource;
    }

    public void setNumberSource(Integer numberSource) {
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

    public String getProtocolBackend() {
        return protocolBackend;
    }

    public void setProtocolBackend(String protocolBackend) {
        this.protocolBackend = protocolBackend;
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

    public Integer getMarketingOccupancyType() {
        return marketingOccupancyType;
    }

    public void setMarketingOccupancyType(Integer marketingOccupancyType) {
        this.marketingOccupancyType = marketingOccupancyType;
    }

    public Long getMarketingOccupancyTaskId() {
        return marketingOccupancyTaskId;
    }

    public void setMarketingOccupancyTaskId(Long marketingOccupancyTaskId) {
        this.marketingOccupancyTaskId = marketingOccupancyTaskId;
    }

    public Long getMarketingLockedAt() {
        return marketingLockedAt;
    }

    public void setMarketingLockedAt(Long marketingLockedAt) {
        this.marketingLockedAt = marketingLockedAt;
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

    public String getCountryFlag() {
        return countryFlag;
    }

    public void setCountryFlag(String countryFlag) {
        this.countryFlag = countryFlag;
    }

    public String getIpSource() {
        return ipSource;
    }

    public void setIpSource(String ipSource) {
        this.ipSource = ipSource;
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

    public int getGroupsNum() {
        return groupsNum;
    }

    public void setGroupsNum(int groupsNum) {
        this.groupsNum = groupsNum;
    }
}
