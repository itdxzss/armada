package com.armada.group.model.entity;

/**
 * 群链接导入明细实体,映射 group_link_import_detail 表一行。普通类 + getter/setter(无 Lombok)。
 */
public class GroupLinkImportDetail {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写)。 */
    private Long tenantId;

    /** 所属批次(关联 group_link_import_batch.id)。 */
    private Long batchId;

    /** 拼接后行号。 */
    private int lineNo;

    /** 原文链接(失败行也保留)。 */
    private String rawUrl;

    /** 群名称(可空)。 */
    private String groupName;

    /** 导入结果:1=成功(新增/复活) 2=已存在 3=批内重复 4=格式错误。 */
    private int result;

    /** 失败原因(result≥3时)。 */
    private String failReason;

    /** 成功/已存在时关联 group_link.id(成功=新建或复活的行;已存在=命中的现有行)。 */
    private Long groupLinkId;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

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

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public void setLineNo(int lineNo) {
        this.lineNo = lineNo;
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public void setRawUrl(String rawUrl) {
        this.rawUrl = rawUrl;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
