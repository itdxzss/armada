package com.armada.account.model.entity;

/** 接码订单的 Go 注册办理持久化实体；号码与验证码只在 sms_activation，本类不复制。 */
public class SmsRegistration {
    /** 主键。 */
    private Long id;
    /** 租户 ID，由租户插件写入与过滤。 */
    private Long tenantId;
    /** 接码订单 ID，一个订单最多一条办理记录。 */
    private Long smsActivationId;
    /** 办理状态，见 {@link com.armada.account.model.enums.SmsRegistrationState}。 */
    private Integer state;
    /** Go 注册配置 ID，开始时固定，幂等重放必须一致。 */
    private Long goProfileId;
    /** 开始时冻结的账号类型：1 个人，2 商业。 */
    private Integer accountType;
    /** 本次占用的代理 ID，调用 Go 发码前写入；终态释放成功后清空，非空表示仍需释放。 */
    private Long proxyId;
    /** Go 注册事务 ID，发码成功后写入。 */
    private String goTransactionId;
    /** 发码成功时 Go 返回的 version，只写一次，验码永远使用它。 */
    private Long goVersion;
    /** 需处理原因，见 {@link com.armada.account.model.enums.SmsRegistrationFailure}，其它状态为空串。 */
    private String failureCode;
    /** 点击开始注册的操作人用户 ID。 */
    private Long createdBy;
    /** 创建时间 epoch 毫秒。 */
    private Long createdAt;
    /** 变更时间 epoch 毫秒。 */
    private Long updatedAt;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 租户 ID */
    public Long getTenantId() { return tenantId; }
    /** @param tenantId 租户 ID */
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    /** @return 接码订单 ID */
    public Long getSmsActivationId() { return smsActivationId; }
    /** @param smsActivationId 接码订单 ID */
    public void setSmsActivationId(Long smsActivationId) { this.smsActivationId = smsActivationId; }
    /** @return 办理状态值 */
    public Integer getState() { return state; }
    /** @param state 办理状态值 */
    public void setState(Integer state) { this.state = state; }
    /** @return Go 注册配置 ID */
    public Long getGoProfileId() { return goProfileId; }
    /** @param goProfileId Go 注册配置 ID */
    public void setGoProfileId(Long goProfileId) { this.goProfileId = goProfileId; }
    /** @return 冻结的账号类型 */
    public Integer getAccountType() { return accountType; }
    /** @param accountType 冻结的账号类型 */
    public void setAccountType(Integer accountType) { this.accountType = accountType; }
    /** @return 占用的代理 ID */
    public Long getProxyId() { return proxyId; }
    /** @param proxyId 占用的代理 ID */
    public void setProxyId(Long proxyId) { this.proxyId = proxyId; }
    /** @return Go 事务 ID */
    public String getGoTransactionId() { return goTransactionId; }
    /** @param goTransactionId Go 事务 ID */
    public void setGoTransactionId(String goTransactionId) { this.goTransactionId = goTransactionId; }
    /** @return 发码成功时的 Go version */
    public Long getGoVersion() { return goVersion; }
    /** @param goVersion 发码成功时的 Go version */
    public void setGoVersion(Long goVersion) { this.goVersion = goVersion; }
    /** @return 需处理原因 */
    public String getFailureCode() { return failureCode; }
    /** @param failureCode 需处理原因 */
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    /** @return 操作人用户 ID */
    public Long getCreatedBy() { return createdBy; }
    /** @param createdBy 操作人用户 ID */
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    /** @return 创建时间 */
    public Long getCreatedAt() { return createdAt; }
    /** @param createdAt 创建时间 */
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    /** @return 变更时间 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param updatedAt 变更时间 */
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
