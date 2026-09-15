package com.armada.resource.model.entity;

/** 拉群数据包Phone数据库/查询数据。 */
public class GroupDataPackagePhone {
    /** 号码ID。 */
    private Long id;
    /** 租户ID。 */
    private Long tenantId;
    /** 数据包ID。 */
    private Long packageId;
    /** 号码代次。 */
    private Integer generation;
    /** 来源导入批次。 */
    private Long sourceImportId;
    /** 国际号码。 */
    private String phone;
    /** 国家ISO2。 */
    private String countryIso2;
    /** 稳定顺序。 */
    private Integer memberSeq;
    /** 原始行号。 */
    private Integer sourceLineNo;
    /** 管理员标记。 */
    private Boolean adminRequired;
    /** 号码池状态。 */
    private Integer status;
    /** 占用任务。 */
    private Long claimedTaskId;
    /** 占用任务内逻辑执行序号。 */
    private Integer claimedExecutionSeq;
    /** 分配代次，旧结果不可覆盖新分配。 */
    private Long allocationVersion;
    /** 创建时间。 */
    private Long createdAt;
    /** 更新时间。 */
    private Long updatedAt;
    /** 读取号码ID。 */
    public Long getId() { return id; }
    /** 设置号码ID。 */
    public void setId(Long value) { id = value; }
    /** 读取租户ID。 */
    public Long getTenantId() { return tenantId; }
    /** 设置租户ID。 */
    public void setTenantId(Long value) { tenantId = value; }
    /** 读取数据包ID。 */
    public Long getPackageId() { return packageId; }
    /** 设置数据包ID。 */
    public void setPackageId(Long value) { packageId = value; }
    /** 读取号码代次。 */
    public Integer getGeneration() { return generation; }
    /** 设置号码代次。 */
    public void setGeneration(Integer value) { generation = value; }
    /** 读取来源导入批次。 */
    public Long getSourceImportId() { return sourceImportId; }
    /** 设置来源导入批次。 */
    public void setSourceImportId(Long value) { sourceImportId = value; }
    /** 读取国际号码。 */
    public String getPhone() { return phone; }
    /** 设置国际号码。 */
    public void setPhone(String value) { phone = value; }
    /** 读取国家ISO2。 */
    public String getCountryIso2() { return countryIso2; }
    /** 设置国家ISO2。 */
    public void setCountryIso2(String value) { countryIso2 = value; }
    /** 读取稳定顺序。 */
    public Integer getMemberSeq() { return memberSeq; }
    /** 设置稳定顺序。 */
    public void setMemberSeq(Integer value) { memberSeq = value; }
    /** 读取原始行号。 */
    public Integer getSourceLineNo() { return sourceLineNo; }
    /** 设置原始行号。 */
    public void setSourceLineNo(Integer value) { sourceLineNo = value; }
    /** 读取管理员标记。 */
    public Boolean getAdminRequired() { return adminRequired; }
    /** 设置管理员标记。 */
    public void setAdminRequired(Boolean value) { adminRequired = value; }
    /** 读取号码池状态。 */
    public Integer getStatus() { return status; }
    /** 设置号码池状态。 */
    public void setStatus(Integer value) { status = value; }
    /** 读取占用任务。 */
    public Long getClaimedTaskId() { return claimedTaskId; }
    /** 设置占用任务。 */
    public void setClaimedTaskId(Long value) { claimedTaskId = value; }
    /** 读取占用任务内逻辑执行序号。 */
    public Integer getClaimedExecutionSeq() { return claimedExecutionSeq; }
    /** 设置占用任务内逻辑执行序号。 */
    public void setClaimedExecutionSeq(Integer value) { claimedExecutionSeq = value; }
    /** 读取分配代次，旧结果不可覆盖新分配。 */
    public Long getAllocationVersion() { return allocationVersion; }
    /** 设置分配代次，旧结果不可覆盖新分配。 */
    public void setAllocationVersion(Long value) { allocationVersion = value; }
    /** 读取创建时间。 */
    public Long getCreatedAt() { return createdAt; }
    /** 设置创建时间。 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** 读取更新时间。 */
    public Long getUpdatedAt() { return updatedAt; }
    /** 设置更新时间。 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
}
