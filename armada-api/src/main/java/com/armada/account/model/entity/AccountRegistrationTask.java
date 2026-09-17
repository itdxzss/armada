package com.armada.account.model.entity;

import java.math.BigDecimal;

/** 接码注册任务持久化实体，不保存验证码或六段。 */
public class AccountRegistrationTask {
    /** 执行端，旧任务默认为 Cobalt。 */
    private Integer executionMode = com.armada.account.model.enums.RegistrationExecutionMode.COBALT.code();
    /** 手机任务绑定的设备 UUID。 */
    private String deviceId;
    /** 手机许可允许采购的截止毫秒。 */
    private Long purchaseBefore;
    /** 明确指定的唯一接码商家；NULL 表示按价格目录选择。 */
    private String providerId;
    /** @return 不可变的指定商家约束 */
    public String getProviderId() { return providerId; }
    /** @param value 创建任务时固定的商家码 */
    public void setProviderId(String value) { providerId = value; }
    /** @return 执行端 */
    public Integer getExecutionMode() { return executionMode; }
    /** @param value 执行端 */
    public void setExecutionMode(Integer value) { executionMode = value; }
    /** @return 绑定设备 */
    public String getDeviceId() { return deviceId; }
    /** @param value 绑定设备 */
    public void setDeviceId(String value) { deviceId = value; }
    /** @return 采购截止毫秒 */
    public Long getPurchaseBefore() { return purchaseBefore; }
    /** @param value 采购截止毫秒 */
    public void setPurchaseBefore(Long value) { purchaseBefore = value; }
    /** 任务主键。 */
    private Long id;
    /** 所属租户。 */
    private Long tenantId;
    /** 租户内幂等键。 */
    private String requestId;
    /** 创建时确认的 WhatsApp 服务代码。 */
    private String serviceCode;
    /** 供应商美国目录ID。 */
    private String countryId;
    /** 固定采购单价，不隐含币种。 */
    private BigDecimal unitPrice;
    /** 采购尝试总次数。 */
    private Integer quantity;
    /** 目标现有账号分组。 */
    private Long accountGroupId;
    /** 个人1或商业2。 */
    private Integer accountType;
    /** smart或mixed。 */
    private String ipAllocationMode;
    /** 历史代理地区兼容字段。 */
    private String ipRegion;
    /** 停止未采购条目。 */
    private Boolean cancelRequested;
    /** 创建时间毫秒。 */
    private Long createdAt;
    /** 变更时间毫秒。 */
    private Long updatedAt;
    /** @return 任务主键 */
    public Long getId() { return id; }
    /** @param value 任务主键 */
    public void setId(Long value) { id = value; }
    /** @return 所属租户 */
    public Long getTenantId() { return tenantId; }
    /** @param value 所属租户 */
    public void setTenantId(Long value) { tenantId = value; }
    /** @return 租户内幂等键 */
    public String getRequestId() { return requestId; }
    /** @param value 租户内幂等键 */
    public void setRequestId(String value) { requestId = value; }
    /** @return 创建时确认的 WhatsApp 服务代码 */
    public String getServiceCode() { return serviceCode; }
    /** @param value 创建时确认的 WhatsApp 服务代码 */
    public void setServiceCode(String value) { serviceCode = value; }
    /** @return 供应商美国目录ID */
    public String getCountryId() { return countryId; }
    /** @param value 供应商美国目录ID */
    public void setCountryId(String value) { countryId = value; }
    /** @return 固定采购单价，不隐含币种 */
    public BigDecimal getUnitPrice() { return unitPrice; }
    /** @param value 固定采购单价，不隐含币种 */
    public void setUnitPrice(BigDecimal value) { unitPrice = value; }
    /** @return 采购尝试总次数 */
    public Integer getQuantity() { return quantity; }
    /** @param value 采购尝试总次数 */
    public void setQuantity(Integer value) { quantity = value; }
    /** @return 目标现有账号分组 */
    public Long getAccountGroupId() { return accountGroupId; }
    /** @param value 目标现有账号分组 */
    public void setAccountGroupId(Long value) { accountGroupId = value; }
    /** @return 个人1或商业2 */
    public Integer getAccountType() { return accountType; }
    /** @param value 个人1或商业2 */
    public void setAccountType(Integer value) { accountType = value; }
    /** @return smart或mixed */
    public String getIpAllocationMode() { return ipAllocationMode; }
    /** @param value smart或mixed */
    public void setIpAllocationMode(String value) { ipAllocationMode = value; }
    /** @return 历史代理地区兼容字段 */
    public String getIpRegion() { return ipRegion; }
    /** @param value 历史代理地区兼容字段 */
    public void setIpRegion(String value) { ipRegion = value; }
    /** @return 停止未采购条目 */
    public Boolean getCancelRequested() { return cancelRequested; }
    /** @param value 停止未采购条目 */
    public void setCancelRequested(Boolean value) { cancelRequested = value; }
    /** @return 创建时间毫秒 */
    public Long getCreatedAt() { return createdAt; }
    /** @param value 创建时间毫秒 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** @return 变更时间毫秒 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param value 变更时间毫秒 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
}
