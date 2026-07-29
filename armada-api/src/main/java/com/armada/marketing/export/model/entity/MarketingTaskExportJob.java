package com.armada.marketing.export.model.entity;

/** 营销任务导出作业，映射 {@code marketing_task_export_job}。 */
public class MarketingTaskExportJob {

    private Long id;
    private Long tenantId;
    private Long createdBy;
    private String exportMode;
    private String taskIdsJson;
    private String countryIso2sJson;
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
    private Integer summaryRowCount;
    private Integer detailRowCount;
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
    public String getExportMode() { return exportMode; }
    public void setExportMode(String exportMode) { this.exportMode = exportMode; }
    public String getTaskIdsJson() { return taskIdsJson; }
    public void setTaskIdsJson(String taskIdsJson) { this.taskIdsJson = taskIdsJson; }
    public String getCountryIso2sJson() { return countryIso2sJson; }
    public void setCountryIso2sJson(String countryIso2sJson) { this.countryIso2sJson = countryIso2sJson; }
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
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getSummaryRowCount() { return summaryRowCount; }
    public void setSummaryRowCount(Integer summaryRowCount) { this.summaryRowCount = summaryRowCount; }
    public Integer getDetailRowCount() { return detailRowCount; }
    public void setDetailRowCount(Integer detailRowCount) { this.detailRowCount = detailRowCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
