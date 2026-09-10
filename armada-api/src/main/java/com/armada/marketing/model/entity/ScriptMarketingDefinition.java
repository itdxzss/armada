package com.armada.marketing.model.entity;

/** 可复用养群剧本定义，与任务运行实例分开保存。 */
public class ScriptMarketingDefinition {
    /** 剧本 ID。 */
    private Long id;
    /** 读取剧本 ID。 */
    public Long getId() { return id; }
    /** 保存剧本 ID。 */
    public void setId(Long value) { id = value; }
    /** 租户 ID。 */
    private Long tenantId;
    /** 读取租户 ID。 */
    public Long getTenantId() { return tenantId; }
    /** 保存租户 ID。 */
    public void setTenantId(Long value) { tenantId = value; }
    /** 创建用户。 */
    private Long createdBy;
    /** 读取创建用户。 */
    public Long getCreatedBy() { return createdBy; }
    /** 保存创建用户。 */
    public void setCreatedBy(Long value) { createdBy = value; }
    /** 剧本名称。 */
    private String name;
    /** 读取剧本名称。 */
    public String getName() { return name; }
    /** 保存剧本名称。 */
    public void setName(String value) { name = value; }
    /** 有序角色与消息内容快照。 */
    private String stepsJson;
    /** 读取有序角色与消息内容快照。 */
    public String getStepsJson() { return stepsJson; }
    /** 保存有序角色与消息内容快照。 */
    public void setStepsJson(String value) { stepsJson = value; }
    /** 是否允许选用。 */
    private Boolean enabled;
    /** 读取是否允许选用。 */
    public Boolean getEnabled() { return enabled; }
    /** 保存是否允许选用。 */
    public void setEnabled(Boolean value) { enabled = value; }
    /** 创建时间。 */
    private Long createdAt;
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 保存创建时间。 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** 更新时间。 */
    private Long updatedAt;
    /** 读取更新时间。 */
    public Long getUpdatedAt() { return updatedAt; }
    /** 保存更新时间。 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
    /** 软删除时间。 */
    private Long deletedAt;
    /** 读取软删除时间。 */
    public Long getDeletedAt() { return deletedAt; }
    /** 保存软删除时间。 */
    public void setDeletedAt(Long value) { deletedAt = value; }
}
