package com.armada.group.model.entity;

import java.time.LocalDateTime;

/**
 * 群链接实体,映射 group_link 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。
 */
public class GroupLink {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写)。 */
    private Long tenantId;

    /** 归一化后群邀请链接(租户内唯一,按字节精确去重)。 */
    private String linkUrl;

    /** 业务群名(导入时填,可空)。 */
    private String groupName;

    /** 所属WS链接分组(关联 group_link_label.id;只导入链接菜单写)。 */
    private Long labelId;

    /** 来源导入批次(关联 group_link_import_batch.id)。 */
    private Long importBatchId;

    /** 备注(纯导入备注)。 */
    private String remark;

    /** 创建时间(UTC)。 */
    private LocalDateTime createdAt;

    /** 更新时间(UTC)。 */
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

    public String getLinkUrl() {
        return linkUrl;
    }

    public void setLinkUrl(String linkUrl) {
        this.linkUrl = linkUrl;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public Long getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
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
