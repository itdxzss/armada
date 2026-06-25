package com.armada.account.model.entity;

/**
 * 账号自托管凭据实体,映射 account_credential 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 * is_active 是 DB 虚拟列,实体不映射。
 * 铁律:日志只打 maskPhone+凭据长度,绝不打 creds_json 明文。
 */
public class AccountCredential {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入)。 */
    private Long tenantId;

    /** 关联账号 ID(→account.id)。 */
    private Long accountId;

    /** WA 号(冗余便反查)。 */
    private String wsPhone;

    /** 凭据格式:1六段 2JSON 3全参。 */
    private Integer credFormat;

    /** 完整凭据 blob(敏感,日志只打 maskPhone+长度)。 */
    private String credsJson;

    /** sticky 代理 session(同 IP 复用键;上线时填)。 */
    private String proxySessionId;

    /** 代理 session 保留到期(epoch 毫秒;下线时填)。 */
    private Long proxyRetainUntil;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public Integer getCredFormat() {
        return credFormat;
    }

    public void setCredFormat(Integer credFormat) {
        this.credFormat = credFormat;
    }

    public String getCredsJson() {
        return credsJson;
    }

    public void setCredsJson(String credsJson) {
        this.credsJson = credsJson;
    }

    public String getProxySessionId() {
        return proxySessionId;
    }

    public void setProxySessionId(String proxySessionId) {
        this.proxySessionId = proxySessionId;
    }

    public Long getProxyRetainUntil() {
        return proxyRetainUntil;
    }

    public void setProxyRetainUntil(Long proxyRetainUntil) {
        this.proxyRetainUntil = proxyRetainUntil;
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
