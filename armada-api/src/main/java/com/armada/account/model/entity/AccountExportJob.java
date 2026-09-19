package com.armada.account.model.entity;

/** 持久化账号导出作业；禁止直接序列化到 API 或日志。 */
public class AccountExportJob {
    /** 导出作业 UUID，也是请求幂等键。 */
    private String id;
    /** 作业租户。 */
    private Long tenantId;
    /** 创建用户。 */
    private Long createdBy;
    /** READY/COMPLETED/CANCELLED。 */
    private String status;
    /** 固定账号 ID 集合。 */
    private String accountIdsJson;
    /** 账号数量。 */
    private Integer accountCount;
    /** 下载文件名。 */
    private String filename;
    /** 产物 SHA-256。 */
    private String sha256;
    /** ZIP 字节数，用于客户端验证完整接收。 */
    private Integer fileSize;

    public Integer getFileSize() { return fileSize; }
    public void setFileSize(Integer value) { fileSize = value; }
    /** 敏感 ZIP 字节，仅文件接口可访问。 */
    private byte[] archive;
    /** 创建时间。 */
    private Long createdAt;
    /** 下载到期时间。 */
    private Long expiresAt;
    /** 控端移除完成时间。 */
    private Long completedAt;
    /** 读取导出作业 UUID，也是请求幂等键。 */
    public String getId() { return id; }
    /** 设置导出作业 UUID，也是请求幂等键。 */
    public void setId(String value) { id = value; }
    /** 读取作业租户。 */
    public Long getTenantId() { return tenantId; }
    /** 设置作业租户。 */
    public void setTenantId(Long value) { tenantId = value; }
    /** 读取创建用户。 */
    public Long getCreatedBy() { return createdBy; }
    /** 设置创建用户。 */
    public void setCreatedBy(Long value) { createdBy = value; }
    /** 读取READY/COMPLETED/CANCELLED。 */
    public String getStatus() { return status; }
    /** 设置READY/COMPLETED/CANCELLED。 */
    public void setStatus(String value) { status = value; }
    /** 读取固定账号 ID 集合。 */
    public String getAccountIdsJson() { return accountIdsJson; }
    /** 设置固定账号 ID 集合。 */
    public void setAccountIdsJson(String value) { accountIdsJson = value; }
    /** 读取账号数量。 */
    public Integer getAccountCount() { return accountCount; }
    /** 设置账号数量。 */
    public void setAccountCount(Integer value) { accountCount = value; }
    /** 读取下载文件名。 */
    public String getFilename() { return filename; }
    /** 设置下载文件名。 */
    public void setFilename(String value) { filename = value; }
    /** 读取产物 SHA-256。 */
    public String getSha256() { return sha256; }
    /** 设置产物 SHA-256。 */
    public void setSha256(String value) { sha256 = value; }
    /** 读取敏感 ZIP 字节，仅文件接口可访问。 */
    public byte[] getArchive() { return archive; }
    /** 设置敏感 ZIP 字节，仅文件接口可访问。 */
    public void setArchive(byte[] value) { archive = value; }
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 设置创建时间。 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** 读取下载到期时间。 */
    public Long getExpiresAt() { return expiresAt; }
    /** 设置下载到期时间。 */
    public void setExpiresAt(Long value) { expiresAt = value; }
    /** 读取控端移除完成时间。 */
    public Long getCompletedAt() { return completedAt; }
    /** 设置控端移除完成时间。 */
    public void setCompletedAt(Long value) { completedAt = value; }
}
