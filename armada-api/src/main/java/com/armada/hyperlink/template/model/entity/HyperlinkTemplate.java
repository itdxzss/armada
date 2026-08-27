package com.armada.hyperlink.template.model.entity;

/** 超链营销模板实体，映射 {@code hyperlink_template} 表。 */
public class HyperlinkTemplate {

    /** 模板主键。 */
    private Long id;
    /** 租户主键。 */
    private Long tenantId;
    /** 模板名称。 */
    private String templateName;
    /** 消息类型。 */
    private Integer messageType;
    /** 消息结构版本。 */
    private Integer messageSchemaVersion;
    /** 链接标题。 */
    private String title;
    /** 正文。 */
    private String content;
    /** 链接描述。 */
    private String linkDescription;
    /** 推广链接。 */
    private String promotionLink;
    /** 按钮 JSON。 */
    private String buttons;
    /** 卡片文本。 */
    private String cardText;
    /** 链接预览图片文件主键。 */
    private Long linkPreviewAssetId;
    /** 正文主图文件主键。 */
    private Long bodyMainAssetId;
    /** 备注。 */
    private String remark;
    /** 乐观锁版本。 */
    private Integer version;
    /** 创建人。 */
    private Long createdBy;
    /** 创建时间戳。 */
    private Long createdAt;
    /** 更新时间戳。 */
    private Long updatedAt;
    /** 软删除时间戳。 */
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

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public Integer getMessageType() {
        return messageType;
    }

    public void setMessageType(Integer messageType) {
        this.messageType = messageType;
    }

    public Integer getMessageSchemaVersion() {
        return messageSchemaVersion;
    }

    public void setMessageSchemaVersion(Integer messageSchemaVersion) {
        this.messageSchemaVersion = messageSchemaVersion;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLinkDescription() {
        return linkDescription;
    }

    public void setLinkDescription(String linkDescription) {
        this.linkDescription = linkDescription;
    }

    public String getPromotionLink() {
        return promotionLink;
    }

    public void setPromotionLink(String promotionLink) {
        this.promotionLink = promotionLink;
    }

    public String getButtons() {
        return buttons;
    }

    public void setButtons(String buttons) {
        this.buttons = buttons;
    }

    public String getCardText() {
        return cardText;
    }

    public void setCardText(String cardText) {
        this.cardText = cardText;
    }

    public Long getLinkPreviewAssetId() {
        return linkPreviewAssetId;
    }

    public void setLinkPreviewAssetId(Long linkPreviewAssetId) {
        this.linkPreviewAssetId = linkPreviewAssetId;
    }

    public Long getBodyMainAssetId() {
        return bodyMainAssetId;
    }

    public void setBodyMainAssetId(Long bodyMainAssetId) {
        this.bodyMainAssetId = bodyMainAssetId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
