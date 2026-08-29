package com.armada.marketing.asset.model.entity;

/** 图片素材标签字典实体。 */
public class ResourceAssetTag {

    /** 标签主键。 */
    private Long id;
    /** 租户 ID，由 MyBatis 租户插件自动写入和隔离。 */
    private Long tenantId;
    /** trim 后大小写敏感的标签名。 */
    private String tagName;
    /** 标签首次创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** @return 标签主键 */
    public Long getId() {
        return id;
    }

    /** @param id 标签主键 */
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

    /** @return 大小写敏感标签名 */
    public String getTagName() {
        return tagName;
    }

    /** @param tagName 大小写敏感标签名 */
    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    /** @return 标签首次创建时间，epoch 毫秒 */
    public Long getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt 标签首次创建时间，epoch 毫秒 */
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
