package com.armada.group.model.entity;

/**
 * 群链接导入批次实体,映射 group_link_import_batch 表一行。普通类 + getter/setter(无 Lombok)。
 */
public class GroupLinkImportBatch {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入,不手写)。 */
    private Long tenantId;

    /** 导入目标WS链接分组(关联 group_link_label.id)。 */
    private Long labelId;

    /** 来源文件/批次名称(用户填)。 */
    private String batchName;

    /** 上传文件原名;纯 text 导入=NULL。 */
    private String sourceFileName;

    /** 解析总行数。 */
    private int totalRows;

    /** 新增行数。 */
    private int insertedRows;

    /**
     * 已存在行数(EXISTS:同 url 已活跃存在、未导入)。
     * 注:沿用既有 {@code adopted_rows} 列暂存(「收编」已废);列名改 {@code exists_rows} 待与 account 迁移序列协调后做(TODO)。
     */
    private int adoptedRows;

    /** 批内重复跳过行数。 */
    private int skippedRows;

    /** 格式不合格行数。 */
    private int failedRows;

    /** 导入时间(epoch毫秒)。 */
    private Long createdAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删除时间(epoch毫秒);随分组级联软删。 */
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

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public String getBatchName() {
        return batchName;
    }

    public void setBatchName(String batchName) {
        this.batchName = batchName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getInsertedRows() {
        return insertedRows;
    }

    public void setInsertedRows(int insertedRows) {
        this.insertedRows = insertedRows;
    }

    public int getAdoptedRows() {
        return adoptedRows;
    }

    public void setAdoptedRows(int adoptedRows) {
        this.adoptedRows = adoptedRows;
    }

    public int getSkippedRows() {
        return skippedRows;
    }

    public void setSkippedRows(int skippedRows) {
        this.skippedRows = skippedRows;
    }

    public int getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(int failedRows) {
        this.failedRows = failedRows;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
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
