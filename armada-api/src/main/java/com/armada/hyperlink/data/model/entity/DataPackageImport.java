package com.armada.hyperlink.data.model.entity;

/** 一次数据包 TXT 导入的独立审计实体。 */
public class DataPackageImport {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 目标数据包 ID。 */
    private Long dataPackageId;
    /** 实际写入的号码代次。 */
    private Integer generation;
    /** 导入模式码。 */
    private Integer importMode;
    /** 导入状态码。 */
    private Integer status;
    /** 原始文件名。 */
    private String sourceFileName;
    /** 非空行总数。 */
    private Integer totalRows;
    /** 接受写入的唯一号码数。 */
    private Integer acceptedRows;
    /** 非法号码行数。 */
    private Integer invalidRows;
    /** 文件内及包内重复行数。 */
    private Integer duplicatedRows;
    /** 失败原因摘要。 */
    private String failureReason;
    /** 创建用户 ID。 */
    private Long createdBy;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 完成时间（epoch 毫秒）。 */
    private Long finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long dataPackageId) { this.dataPackageId = dataPackageId; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public Integer getImportMode() { return importMode; }
    public void setImportMode(Integer importMode) { this.importMode = importMode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getAcceptedRows() { return acceptedRows; }
    public void setAcceptedRows(Integer acceptedRows) { this.acceptedRows = acceptedRows; }
    public Integer getInvalidRows() { return invalidRows; }
    public void setInvalidRows(Integer invalidRows) { this.invalidRows = invalidRows; }
    public Integer getDuplicatedRows() { return duplicatedRows; }
    public void setDuplicatedRows(Integer duplicatedRows) { this.duplicatedRows = duplicatedRows; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
}
