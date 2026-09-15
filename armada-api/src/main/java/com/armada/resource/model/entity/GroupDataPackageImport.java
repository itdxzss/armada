package com.armada.resource.model.entity;

/** 拉群数据包Import数据库/查询数据。 */
public class GroupDataPackageImport {
    /** 导入ID。 */
    private Long id;
    /** 包ID。 */
    private Long packageId;
    /** 代次。 */
    private Integer generation;
    /** 模式。 */
    private Integer mode;
    /** 文件名。 */
    private String fileName;
    /** 导入状态1处理中2成功3失败。 */
    private Integer status;
    /** totalRows。 */
    private Integer totalRows;
    /** acceptedRows。 */
    private Integer acceptedRows;
    /** invalidRows。 */
    private Integer invalidRows;
    /** duplicatedRows。 */
    private Integer duplicatedRows;
    /** privacyFilteredRows。 */
    private Integer privacyFilteredRows;
    /** 失败原因。 */
    private String failureReason;
    /** 创建人。 */
    private Long createdBy;
    /** 创建时间。 */
    private Long createdAt;
    /** 完成时间。 */
    private Long finishedAt;
    /** 读取导入ID。 */
    public Long getId() { return id; }
    /** 设置导入ID。 */
    public void setId(Long value) { id = value; }
    /** 读取包ID。 */
    public Long getPackageId() { return packageId; }
    /** 设置包ID。 */
    public void setPackageId(Long value) { packageId = value; }
    /** 读取代次。 */
    public Integer getGeneration() { return generation; }
    /** 设置代次。 */
    public void setGeneration(Integer value) { generation = value; }
    /** 读取模式。 */
    public Integer getMode() { return mode; }
    /** 设置模式。 */
    public void setMode(Integer value) { mode = value; }
    /** 读取文件名。 */
    public String getFileName() { return fileName; }
    /** 设置文件名。 */
    public void setFileName(String value) { fileName = value; }
    /** 读取导入状态1处理中2成功3失败。 */
    public Integer getStatus() { return status; }
    /** 设置导入状态1处理中2成功3失败。 */
    public void setStatus(Integer value) { status = value; }
    /** 读取totalRows。 */
    public Integer getTotalRows() { return totalRows; }
    /** 设置totalRows。 */
    public void setTotalRows(Integer value) { totalRows = value; }
    /** 读取acceptedRows。 */
    public Integer getAcceptedRows() { return acceptedRows; }
    /** 设置acceptedRows。 */
    public void setAcceptedRows(Integer value) { acceptedRows = value; }
    /** 读取invalidRows。 */
    public Integer getInvalidRows() { return invalidRows; }
    /** 设置invalidRows。 */
    public void setInvalidRows(Integer value) { invalidRows = value; }
    /** 读取duplicatedRows。 */
    public Integer getDuplicatedRows() { return duplicatedRows; }
    /** 设置duplicatedRows。 */
    public void setDuplicatedRows(Integer value) { duplicatedRows = value; }
    /** 读取privacyFilteredRows。 */
    public Integer getPrivacyFilteredRows() { return privacyFilteredRows; }
    /** 设置privacyFilteredRows。 */
    public void setPrivacyFilteredRows(Integer value) { privacyFilteredRows = value; }
    /** 读取失败原因。 */
    public String getFailureReason() { return failureReason; }
    /** 设置失败原因。 */
    public void setFailureReason(String value) { failureReason = value; }
    /** 读取创建人。 */
    public Long getCreatedBy() { return createdBy; }
    /** 设置创建人。 */
    public void setCreatedBy(Long value) { createdBy = value; }
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 设置创建时间。 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** 读取完成时间。 */
    public Long getFinishedAt() { return finishedAt; }
    /** 设置完成时间。 */
    public void setFinishedAt(Long value) { finishedAt = value; }
}
