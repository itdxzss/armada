package com.armada.promotion.channel.model.entity;

/** 推广域名与模板绑定实体。 */
public class PromotionDomain {

    /** 域名绑定记录主键。 */
    private Long id;
    /** 规范化的小写 ASCII/Punycode 域名。 */
    private String domainHost;
    /** 该域名唯一绑定的落地页模板 ID。 */
    private Long landingTemplateId;
    /** 创建人用户 ID。 */
    private Long createdBy;
    /** 最近修改人用户 ID。 */
    private Long updatedBy;
    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;
    /** 更新时间，epoch 毫秒。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDomainHost() {
        return domainHost;
    }

    public void setDomainHost(String domainHost) {
        this.domainHost = domainHost;
    }

    public Long getLandingTemplateId() {
        return landingTemplateId;
    }

    public void setLandingTemplateId(Long landingTemplateId) {
        this.landingTemplateId = landingTemplateId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
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
