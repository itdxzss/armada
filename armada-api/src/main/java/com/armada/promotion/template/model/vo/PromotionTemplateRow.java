package com.armada.promotion.template.model.vo;

/** MyBatis 模板分页投影；JSON 参数先按字符串读取，再由 Service 转成稳定响应。 */
public class PromotionTemplateRow {

    private Long id;
    private String templateCode;
    private String templateName;
    private String previewUri;
    private Integer isSubaccountVisible;
    private String supportedParamsJson;
    private String remark;
    private Long createdAt;
    private Long updatedAt;

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

    public String getPreviewUri() {
        return previewUri;
    }

    public void setPreviewUri(String previewUri) {
        this.previewUri = previewUri;
    }

    public Integer getIsSubaccountVisible() {
        return isSubaccountVisible;
    }

    public void setIsSubaccountVisible(Integer isSubaccountVisible) {
        this.isSubaccountVisible = isSubaccountVisible;
    }

    public String getSupportedParamsJson() {
        return supportedParamsJson;
    }

    public void setSupportedParamsJson(String supportedParamsJson) {
        this.supportedParamsJson = supportedParamsJson;
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
}
