package com.armada.hyperlink.data.model.entity;

/** 某数据包代次内的一条号码成员及当前池状态。 */
public class DataPackagePhone {

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 所属数据包 ID。 */
    private Long dataPackageId;
    /** 所属号码代次。 */
    private Integer generation;
    /** 首次写入该号码的导入审计 ID。 */
    private Long sourceImportId;
    /** 纯数字 E.164 号码。 */
    private String phone;
    /** 可空 ISO2 国家码。 */
    private String countryIso2;
    /** 当前互斥池状态码。 */
    private Integer poolStatus;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long dataPackageId) { this.dataPackageId = dataPackageId; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public Long getSourceImportId() { return sourceImportId; }
    public void setSourceImportId(Long sourceImportId) { this.sourceImportId = sourceImportId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String countryIso2) { this.countryIso2 = countryIso2; }
    public Integer getPoolStatus() { return poolStatus; }
    public void setPoolStatus(Integer poolStatus) { this.poolStatus = poolStatus; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
