package com.armada.account.model.entity;

/**
 * 账号身份主表实体,映射 account 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 * is_active 是 DB 虚拟列,实体不映射。
 */
public class Account {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写 SQL)。 */
    private Long tenantId;

    /** WA 号(按字节精确去重,租户内唯一)。 */
    private String wsPhone;

    /** 当前有效账号类型:1个人 2商业；初始取申报值，协议可靠结果可纠正。 */
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

    /** 商业认证识别来源:1凭据元数据 2配对结果 3商业资料查询。 */
    private Integer businessVerificationSource;

    /** 商业认证级别最后确认时间(epoch 毫秒)。 */
    private Long businessVerificationVerifiedAt;

    /** 机型:1 安卓 2 苹果。 */
    private Integer deviceOs;

    /** 来源:1 买量 2 裂变 3 自购。 */
    private Integer numberSource;

    /** 推广渠道名。 */
    private String channelName;

    /** 推广渠道 ID(落地页配对登录时记录归因渠道)。 */
    private Long promotionChannelId;

    /** 归属:1 自有 2 平台 3 租借。 */
    private Integer ownership;

    /** 租借到期(epoch 毫秒;ownership=3)。 */
    private Long leaseUntil;

    /** 归一单分组(→account_group.id)。 */
    private Long accountGroupId;

    /** 接入协议标识(系统分配)。 */
    private String protocolId;

    /** 协议账号句柄 acc_&lt;wsPhone&gt;。 */
    private String protocolAccountId;

    /** 协议地址。 */
    private String protocolAddress;

    /** 选号优先级。 */
    private Integer priority;

    /** 首次派单时间(epoch 毫秒;step1 恒 NULL=未分配)。 */
    private Long dispatchedAt;

    /** 备注。 */
    private String remark;

    /** 入库时间(epoch 毫秒,应用层写)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒,应用层写)。 */
    private Long updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删时间(epoch 毫秒);NULL=未删。 */
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

    public Long getPromotionChannelId() {
        return promotionChannelId;
    }

    public void setPromotionChannelId(Long promotionChannelId) {
        this.promotionChannelId = promotionChannelId;
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

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public void setProtocolId(String protocolId) {
        this.protocolId = protocolId;
    }

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public String getProtocolAddress() {
        return protocolAddress;
    }

    public void setProtocolAddress(String protocolAddress) {
        this.protocolAddress = protocolAddress;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Long getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Long dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
