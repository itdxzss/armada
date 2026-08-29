package com.armada.account.contact.model.entity;

/** 账号通讯录联系人快照行，对应 account_contact 表。 */
public class AccountContact {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 所属账号 ID。 */
    private Long accountId;
    /** 联系人号码，不带加号的纯数字。 */
    private String contactPhone;
    /** 联系人 JID。 */
    private String contactJid;
    /** 通讯录全名。 */
    private String fullName;
    /** 通讯录名。 */
    private String firstName;
    /** 对方设置的展示名。 */
    private String pushName;
    /** 商业号认证名。 */
    private String businessName;
    /** 通讯录里是否有名字，1 有 0 无。 */
    private Integer isNamed;
    /** 是否双向好友，协议暂不暴露时恒为 0。 */
    private Integer isMutual;
    /** 本行所属同步批次时间（epoch 毫秒）。 */
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

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactJid() {
        return contactJid;
    }

    public void setContactJid(String contactJid) {
        this.contactJid = contactJid;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getPushName() {
        return pushName;
    }

    public void setPushName(String pushName) {
        this.pushName = pushName;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public Integer getIsNamed() {
        return isNamed;
    }

    public void setIsNamed(Integer isNamed) {
        this.isNamed = isNamed;
    }

    public Integer getIsMutual() {
        return isMutual;
    }

    public void setIsMutual(Integer isMutual) {
        this.isMutual = isMutual;
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
