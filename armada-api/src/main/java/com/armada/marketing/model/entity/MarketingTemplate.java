package com.armada.marketing.model.entity;

import java.time.LocalDateTime;

/**
 * 营销模板实体,映射 marketing_template 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。
 *
 * <p>{@code buttons} 以原始 JSON 字符串持久化,转换层(MapStruct converter)在 entity↔VO/DTO
 * 之间做 JSON ↔ {@code List<MessageButton>} 的解析。</p>
 */
public class MarketingTemplate {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 模板名。 */
    private String templateName;

    /** 超链模式:1=普通超链 2=按钮超链(见 {@code LinkMode})。 */
    private Integer linkMode;

    /** 文本类型(搜索筛选,dict 配置)。 */
    private String textType;

    /** 图片文件 ID(≤500KB)。 */
    private Long imageFileId;

    /** 内容(标题/核心卖点)。 */
    private String content;

    /** 文本(正文)。 */
    private String bodyText;

    /** 消息按钮原始 JSON([{type,text,param}],最多 3,仅按钮超链)。 */
    private String buttons;

    /** 推广链接(二期)。 */
    private String promotionLink;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删除时间;NULL=未删。 */
    private LocalDateTime deletedAt;

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

    public Integer getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(Integer linkMode) {
        this.linkMode = linkMode;
    }

    public String getTextType() {
        return textType;
    }

    public void setTextType(String textType) {
        this.textType = textType;
    }

    public Long getImageFileId() {
        return imageFileId;
    }

    public void setImageFileId(Long imageFileId) {
        this.imageFileId = imageFileId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getButtons() {
        return buttons;
    }

    public void setButtons(String buttons) {
        this.buttons = buttons;
    }

    public String getPromotionLink() {
        return promotionLink;
    }

    public void setPromotionLink(String promotionLink) {
        this.promotionLink = promotionLink;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
