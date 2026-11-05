package com.armada.account.model.entity;

/** 一个检测批次的持久事实，不承载协议原始返回。 */
public class AccountTierCheckTask {
    /** 批次主键 */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 数据归属 */
    private Long createdBy;
    /** 创建幂等键 */
    private String requestId;
    /** 标准化创建参数摘要 */
    private String requestFingerprint;
    /** 批次名 */
    private String name;
    /** 1自查2探针 */
    private Integer mode;
    /** 冻结探针ID集合 JSON */
    private String probeAccountIds;
    /** 复查来源批次 */
    private Long sourceTaskId;
    /** 目标账号数 */
    private Integer totalCount;
    /** 1执行中2停止3完成4部分失败 */
    private Integer status;
    /** 创建毫秒 */
    private Long createdAt;
    /** 更新毫秒 */
    private Long updatedAt;
    /** 结束毫秒 */
    private Long finishedAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { this.createdBy = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { this.requestFingerprint = value; }
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    public Integer getMode() { return mode; }
    public void setMode(Integer value) { this.mode = value; }
    public String getProbeAccountIds() { return probeAccountIds; }
    public void setProbeAccountIds(String value) { this.probeAccountIds = value; }
    public Long getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(Long value) { this.sourceTaskId = value; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer value) { this.totalCount = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { this.status = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long value) { this.finishedAt = value; }
}
