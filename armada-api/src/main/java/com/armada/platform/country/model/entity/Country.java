package com.armada.platform.country.model.entity;

/**
 * 国家/地区主数据实体,映射 country 表。无 tenant_id,由 MyBatisConfig 忽略租户拦截。
 */
public class Country {

    /** 主键。 */
    private Long id;

    /** ISO/CLDR 二字母国家/地区码,大写。 */
    private String iso2;

    /** 中文展示名。 */
    private String nameZh;

    /** 英文展示名。 */
    private String nameEn;

    /** 手机号国际区号。 */
    private String phonePrefix;

    /** 国旗 emoji。 */
    private String flag;

    /** 是否启用:1=启用 0=停用。 */
    private Integer isEnabled;

    /** IP 管理是否展示:1=展示 0=不展示。 */
    private Integer isIpSupported;

    /** 排序值,越小越靠前。 */
    private Integer sortOrder;

    /** 备注。 */
    private String remark;

    /** 创建时间(epoch毫秒,应用层写)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒,应用层写)。 */
    private Long updatedAt;

    /** 软删时间(epoch毫秒);NULL=未删。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIso2() {
        return iso2;
    }

    public void setIso2(String iso2) {
        this.iso2 = iso2;
    }

    public String getNameZh() {
        return nameZh;
    }

    public void setNameZh(String nameZh) {
        this.nameZh = nameZh;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public String getPhonePrefix() {
        return phonePrefix;
    }

    public void setPhonePrefix(String phonePrefix) {
        this.phonePrefix = phonePrefix;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public Integer getIsEnabled() {
        return isEnabled;
    }

    public void setIsEnabled(Integer isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Integer getIsIpSupported() {
        return isIpSupported;
    }

    public void setIsIpSupported(Integer isIpSupported) {
        this.isIpSupported = isIpSupported;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
