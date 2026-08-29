package com.armada.hyperlink.task.model.entity;

/** 公共超链导出作业，复用 {@code marketing_task_export_job}。 */
public class HyperlinkTaskExportJob {
    private Long id;
    private Long tenantId;
    private Long createdBy;
    private String dataScopeMode;
    private String exportType;
    private String taskIdsJson;
    private String countryIso2sJson;
    private String requestPayloadJson;
    private String requestHash;
    private String status;
    private Long snapshotAt;
    private Long leaseUntil;
    private String claimToken;
    private Integer attemptCount;
    private String storageKey;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Integer rowCount;
    private String errorMessage;
    private Long createdAt;
    private Long updatedAt;
    private Long finishedAt;
    private Long expiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getDataScopeMode() { return dataScopeMode; }
    public void setDataScopeMode(String value) { this.dataScopeMode = value; }
    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }
    public String getTaskIdsJson() { return taskIdsJson; }
    public void setTaskIdsJson(String value) { this.taskIdsJson = value; }
    public String getCountryIso2sJson() { return countryIso2sJson; }
    public void setCountryIso2sJson(String value) { this.countryIso2sJson = value; }
    public String getRequestPayloadJson() { return requestPayloadJson; }
    public void setRequestPayloadJson(String value) { this.requestPayloadJson = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(Long snapshotAt) { this.snapshotAt = snapshotAt; }
    public Long getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Long leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { this.attemptCount = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { this.contentType = value; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { this.errorMessage = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
