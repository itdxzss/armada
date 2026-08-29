package com.armada.marketing.model.entity;

/**
 * 营销模板图片文件,映射 marketing_template_file。
 */
public class MarketingTemplateFile {

    /** 图片文件主键。 */
    private Long id;
    /** 租户 ID，由 MyBatis 租户插件写入并隔离。 */
    private Long tenantId;
    /** 上传时的原始文件名。 */
    private String originalFilename;
    /** 图片 MIME 类型。 */
    private String contentType;
    /** 原始图片字节数。 */
    private Long sizeBytes;
    /** 原始图片字节。 */
    private byte[] content;
    /** 素材库展示与搜索使用的业务名称。 */
    private String assetName;
    /** 新上传图片解码得到的像素宽度；历史图片允许为空。 */
    private Integer width;
    /** 新上传图片解码得到的像素高度；历史图片允许为空。 */
    private Integer height;
    /** 上传操作人的可信用户 ID，仅用于审计，不代表素材所有权。 */
    private Long createdBy;
    /** 图片创建时间，epoch 毫秒。 */
    private Long createdAt;
    /** 最近一次编辑素材元数据的时间，epoch 毫秒。 */
    private Long updatedAt;
    /** 软删除时间，epoch 毫秒；未删除时为空。 */
    private Long deletedAt;

    /** @return 图片文件主键 */
    public Long getId() {
        return id;
    }

    /** @param id 图片文件主键 */
    public void setId(Long id) {
        this.id = id;
    }

    /** @return 租户 ID */
    public Long getTenantId() {
        return tenantId;
    }

    /** @param tenantId 租户 ID */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    /** @return 上传时的原始文件名 */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /** @param originalFilename 上传时的原始文件名 */
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    /** @return 图片 MIME 类型 */
    public String getContentType() {
        return contentType;
    }

    /** @param contentType 图片 MIME 类型 */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /** @return 原始图片字节数 */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /** @param sizeBytes 原始图片字节数 */
    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /** @return 原始图片字节 */
    public byte[] getContent() {
        return content;
    }

    /** @param content 原始图片字节 */
    public void setContent(byte[] content) {
        this.content = content;
    }

    /** @return 素材库展示与搜索使用的业务名称 */
    public String getAssetName() {
        return assetName;
    }

    /** @param assetName 素材库展示与搜索使用的业务名称 */
    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    /** @return 图片像素宽度；历史图片允许为空 */
    public Integer getWidth() {
        return width;
    }

    /** @param width 图片像素宽度 */
    public void setWidth(Integer width) {
        this.width = width;
    }

    /** @return 图片像素高度；历史图片允许为空 */
    public Integer getHeight() {
        return height;
    }

    /** @param height 图片像素高度 */
    public void setHeight(Integer height) {
        this.height = height;
    }

    /** @return 上传操作人的可信用户 ID */
    public Long getCreatedBy() {
        return createdBy;
    }

    /** @param createdBy 上传操作人的可信用户 ID */
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    /** @return 图片创建时间，epoch 毫秒 */
    public Long getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt 图片创建时间，epoch 毫秒 */
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    /** @return 最近一次编辑素材元数据的时间，epoch 毫秒 */
    public Long getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt 最近一次编辑素材元数据的时间，epoch 毫秒 */
    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** @return 软删除时间，epoch 毫秒；未删除时为空 */
    public Long getDeletedAt() {
        return deletedAt;
    }

    /** @param deletedAt 软删除时间，epoch 毫秒 */
    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
