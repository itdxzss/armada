package com.armada.group.model.entity;

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

    /** 运营侧自定义群名称,群组列表可编辑;导入链接不写。 */
    private String groupName;

    /** 所属WS链接分组(关联 group_link_label.id;只导入链接菜单写)。 */
    private Long labelId;

    /** 来源导入批次(关联 group_link_import_batch.id)。 */
    private Long importBatchId;

    /** 首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群。 */
    private Integer origin;

    /** 我方与群关系:1=目标未进群 2=已进群 3=自建拥有。 */
    private Integer membershipState;

    /** 备注(纯导入备注)。 */
    private String remark;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
    private Long updatedAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删除时间(epoch毫秒);NULL=未删。 */
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

    public Integer getOrigin() {
        return origin;
    }

    public void setOrigin(Integer origin) {
        this.origin = origin;
    }

    public Integer getMembershipState() {
        return membershipState;
    }

    public void setMembershipState(Integer membershipState) {
        this.membershipState = membershipState;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
