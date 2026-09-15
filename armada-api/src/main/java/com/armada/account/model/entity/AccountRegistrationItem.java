package com.armada.account.model.entity;

import java.math.BigDecimal;

/** 接码注册明细持久化实体，不保存验证码或六段。 */
public class AccountRegistrationItem {
    /** 明细主键。 */
    private Long id;
    /** 所属租户。 */
    private Long tenantId;
    /** 所属采购任务。 */
    private Long taskId;
    /** 任务内采购序号。 */
    private Integer ordinal;
    /** 工作流状态码。 */
    private Integer state;
    /** Grizzly接码订单ID。 */
    private String activationId;
    /** 购买号码，不进入日志。 */
    private String phoneNumber;
    /** 平台报告成本。 */
    private BigDecimal actualCost;
    /** 平台报告币种数字代码。 */
    private Integer currency;
    /** 稳定Cobalt注册ID。 */
    private String registrationId;
    /** 现有账号导入批次ID。 */
    private Long importBatchId;
    /** 现有账号ID。 */
    private Long accountId;
    /** 固定安全错误分类。 */
    private String failureCode;
    /** 数据库执行租约令牌。 */
    private String leaseToken;
    /** 租约结束毫秒。 */
    private Long leaseUntil;
    /** 采购意图首次落库时间。 */
    private Long startedAt;
    /** 本地最早取消时间epoch毫秒，与采购意图和执行租约独立。 */
    private Long cancelAfter;
    /** 创建时间毫秒。 */
    private Long createdAt;
    /** 变更时间毫秒。 */
    private Long updatedAt;
    /** @return 明细主键 */
    public Long getId() { return id; }
    /** @param value 明细主键 */
    public void setId(Long value) { id = value; }
    /** @return 所属租户 */
    public Long getTenantId() { return tenantId; }
    /** @param value 所属租户 */
    public void setTenantId(Long value) { tenantId = value; }
    /** @return 所属采购任务 */
    public Long getTaskId() { return taskId; }
    /** @param value 所属采购任务 */
    public void setTaskId(Long value) { taskId = value; }
    /** @return 任务内采购序号 */
    public Integer getOrdinal() { return ordinal; }
    /** @param value 任务内采购序号 */
    public void setOrdinal(Integer value) { ordinal = value; }
    /** @return 工作流状态码 */
    public Integer getState() { return state; }
    /** @param value 工作流状态码 */
    public void setState(Integer value) { state = value; }
    /** @return Grizzly接码订单ID */
    public String getActivationId() { return activationId; }
    /** @param value Grizzly接码订单ID */
    public void setActivationId(String value) { activationId = value; }
    /** @return 购买号码，不进入日志 */
    public String getPhoneNumber() { return phoneNumber; }
    /** @param value 购买号码，不进入日志 */
    public void setPhoneNumber(String value) { phoneNumber = value; }
    /** @return 平台报告成本 */
    public BigDecimal getActualCost() { return actualCost; }
    /** @param value 平台报告成本 */
    public void setActualCost(BigDecimal value) { actualCost = value; }
    /** @return 平台报告币种数字代码 */
    public Integer getCurrency() { return currency; }
    /** @param value 平台报告币种数字代码 */
    public void setCurrency(Integer value) { currency = value; }
    /** @return 稳定Cobalt注册ID */
    public String getRegistrationId() { return registrationId; }
    /** @param value 稳定Cobalt注册ID */
    public void setRegistrationId(String value) { registrationId = value; }
    /** @return 现有账号导入批次ID */
    public Long getImportBatchId() { return importBatchId; }
    /** @param value 现有账号导入批次ID */
    public void setImportBatchId(Long value) { importBatchId = value; }
    /** @return 现有账号ID */
    public Long getAccountId() { return accountId; }
    /** @param value 现有账号ID */
    public void setAccountId(Long value) { accountId = value; }
    /** @return 固定安全错误分类 */
    public String getFailureCode() { return failureCode; }
    /** @param value 固定安全错误分类 */
    public void setFailureCode(String value) { failureCode = value; }
    /** @return 数据库执行租约令牌 */
    public String getLeaseToken() { return leaseToken; }
    /** @param value 数据库执行租约令牌 */
    public void setLeaseToken(String value) { leaseToken = value; }
    /** @return 租约结束毫秒 */
    public Long getLeaseUntil() { return leaseUntil; }
    /** @param value 租约结束毫秒 */
    public void setLeaseUntil(Long value) { leaseUntil = value; }
    /** @return 采购意图首次落库时间 */
    public Long getStartedAt() { return startedAt; }
    /** @param value 采购意图首次落库时间 */
    public void setStartedAt(Long value) { startedAt = value; }
    /** @return 本地最早取消时间epoch毫秒 */
    public Long getCancelAfter() { return cancelAfter; }
    /** @param value 本地最早取消时间epoch毫秒 */
    public void setCancelAfter(Long value) { cancelAfter = value; }
    /** @return 创建时间毫秒 */
    public Long getCreatedAt() { return createdAt; }
    /** @param value 创建时间毫秒 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** @return 变更时间毫秒 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param value 变更时间毫秒 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
}
