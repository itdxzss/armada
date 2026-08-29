package com.armada.hyperlink.task.model.entity;

/**
 * 超链任务异步导出作业，复用 {@code marketing_task_export_job} 表。
 *
 * <p>当前收信人流水导出使用该实体创建、领取和完成 {@code RECIPIENTS}
 * 作业；公共的作业状态与下载入口也会用它读取账号统计、深度归因和访问趋势导出作业。</p>
 *
 * <p>作业通过请求指纹避免重复创建，通过租约和领取令牌防止多个 worker 重复完成。
 * 文件信息只在生成成功后有值；本类的时间字段均为 Unix 毫秒时间戳。</p>
 */
public class HyperlinkTaskExportJob {

    /** 导出作业主键。 */
    private Long id;

    /** 作业所属租户 ID，用于租户隔离和 worker 领取校验。 */
    private Long tenantId;

    /** 发起导出的用户 ID，作业查询和下载只对创建人可见。 */
    private Long createdBy;

    /** 创建作业时固化的数据权限范围；当前收信人导出写入 {@code ALL}。 */
    private String dataScopeMode;

    /**
     * 导出类型，对应表中的 {@code export_mode}。
     * 超链业务支持 {@code RECIPIENTS}、{@code ACCOUNT_STATS}、
     * {@code ATTRIBUTION} 和 {@code VISIT_TREND}。
     */
    private String exportType;

    /** 任务 ID 的 JSON 数组；当前收信人导出每个作业只包含一个超链任务。 */
    private String taskIdsJson;

    /** 创建作业时固化的国家范围 JSON 数组；收信人导出的具体国家条件保存在请求快照中。 */
    private String countryIso2sJson;

    /** 归一化后的导出请求 JSON 快照，保存筛选与排序条件，不包含页码。 */
    private String requestPayloadJson;

    /** 基于导出类型、任务和请求快照计算的 SHA-256 指纹，用于复用未结束的同等作业。 */
    private String requestHash;

    /**
     * 持久化的作业状态：{@code PENDING}、{@code PROCESSING}、
     * {@code SUCCESS} 或 {@code FAILED}。{@code EXPIRED} 由查询服务根据过期时间与文件是否存在派生。
     */
    private String status;

    /** 作业创建时的数据快照上界，生成文件时用它排除后续新增数据。 */
    private Long snapshotAt;

    /** 当前 worker 的处理租约到期时间；超时后其他 worker 可重新领取。 */
    private Long leaseUntil;

    /** 本次领取的唯一令牌，续租和完成时必须匹配，防止过期 worker 回写结果。 */
    private String claimToken;

    /** 已领取处理的次数；当前收信人导出最多尝试 3 次。 */
    private Integer attemptCount;

    /** 导出文件在配置存储根目录下的相对路径；文件过期清理后置空。 */
    private String storageKey;

    /** 用户下载时使用的文件名。 */
    private String fileName;

    /** 导出文件的 MIME 类型。 */
    private String contentType;

    /** 导出文件大小，单位为字节。 */
    private Long fileSize;

    /** 明细导出行数，对应表中的 {@code detail_row_count}。 */
    private Integer rowCount;

    /** 作业失败原因；重新领取或成功后清空。 */
    private String errorMessage;

    /** 作业创建时间。 */
    private Long createdAt;

    /** 作业最后更新时间。 */
    private Long updatedAt;

    /** 作业最终成功或失败的时间。 */
    private Long finishedAt;

    /** 导出文件失效时间；到期后不再允许下载并由清理任务删除文件。 */
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
