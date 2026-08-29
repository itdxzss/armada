package com.armada.hyperlink.task.model.entity;

/** 超链任务消息内容冻结快照。 */
public class HyperlinkTaskContent {
    private Long hyperlinkTaskId;
    private Long tenantId;
    private Integer messageSchemaVersion;
    private Integer messageType;
    private String title;
    private String content;
    private String linkDescription;
    private String promotionLink;
    private String buttons;
    private String cardText;
    private Long linkPreviewAssetId;
    private Long bodyMainAssetId;
    private Long createdAt;
    private Long updatedAt;

    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Integer getMessageSchemaVersion() { return messageSchemaVersion; }
    public void setMessageSchemaVersion(Integer value) { this.messageSchemaVersion = value; }
    public Integer getMessageType() { return messageType; }
    public void setMessageType(Integer value) { this.messageType = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public String getContent() { return content; }
    public void setContent(String value) { this.content = value; }
    public String getLinkDescription() { return linkDescription; }
    public void setLinkDescription(String value) { this.linkDescription = value; }
    public String getPromotionLink() { return promotionLink; }
    public void setPromotionLink(String value) { this.promotionLink = value; }
    public String getButtons() { return buttons; }
    public void setButtons(String value) { this.buttons = value; }
    public String getCardText() { return cardText; }
    public void setCardText(String value) { this.cardText = value; }
    public Long getLinkPreviewAssetId() { return linkPreviewAssetId; }
    public void setLinkPreviewAssetId(Long value) { this.linkPreviewAssetId = value; }
    public Long getBodyMainAssetId() { return bodyMainAssetId; }
    public void setBodyMainAssetId(Long value) { this.bodyMainAssetId = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
