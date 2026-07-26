package com.armada.promotion.channel.model.entity;

/** 渠道新增时使用的可用落地页模板最小实体。 */
public class PromotionLandingTemplate {

    /** 模板主键。 */
    private Long id;
    /** 稳定模板编码。 */
    private String templateCode;
    /** 运营展示名称。 */
    private String templateName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }
}
