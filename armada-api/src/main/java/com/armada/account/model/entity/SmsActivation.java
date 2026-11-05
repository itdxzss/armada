package com.armada.account.model.entity;

import java.math.BigDecimal;

/** 后台手动接码订单持久化实体；号码与验证码禁止写入日志。 */
public class SmsActivation {
    /** 主键。 */
    private Long id;
    /** 租户 ID，由租户插件写入与过滤。 */
    private Long tenantId;
    /** Grizzly 订单 ID。 */
    private String activationId;
    /** 供应商返回的完整国际号码。 */
    private String phoneNumber;
    /** 供应商服务代码。 */
    private String serviceCode;
    /** 供应商国家目录 ID。 */
    private String countryId;
    /** 供应商返回的实际花费。 */
    private BigDecimal cost;
    /** 供应商返回的 ISO 4217 数字币种。 */
    private Integer currency;
    /** 订单状态，见 {@link com.armada.account.model.enums.SmsActivationState}。 */
    private Integer state;
    /** 收到的验证码。 */
    private String smsCode;
    /** 收到验证码时间 epoch 毫秒。 */
    private Long receivedAt;
    /** 最早可取消时间 epoch 毫秒。 */
    private Long cancelAfter;
    /** 号码到期时间 epoch 毫秒。 */
    private Long expiresAt;
    /** 取号操作人用户 ID。 */
    private Long createdBy;
    /** 创建时间 epoch 毫秒。 */
    private Long createdAt;
    /** 变更时间 epoch 毫秒。 */
    private Long updatedAt;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param value 主键 */
    public void setId(Long value) { id = value; }
    /** @return 租户 ID */
    public Long getTenantId() { return tenantId; }
    /** @param value 租户 ID */
    public void setTenantId(Long value) { tenantId = value; }
    /** @return Grizzly 订单 ID */
    public String getActivationId() { return activationId; }
    /** @param value Grizzly 订单 ID */
    public void setActivationId(String value) { activationId = value; }
    /** @return 完整号码 */
    public String getPhoneNumber() { return phoneNumber; }
    /** @param value 完整号码 */
    public void setPhoneNumber(String value) { phoneNumber = value; }
    /** @return 服务代码 */
    public String getServiceCode() { return serviceCode; }
    /** @param value 服务代码 */
    public void setServiceCode(String value) { serviceCode = value; }
    /** @return 国家目录 ID */
    public String getCountryId() { return countryId; }
    /** @param value 国家目录 ID */
    public void setCountryId(String value) { countryId = value; }
    /** @return 实际花费 */
    public BigDecimal getCost() { return cost; }
    /** @param value 实际花费 */
    public void setCost(BigDecimal value) { cost = value; }
    /** @return 数字币种 */
    public Integer getCurrency() { return currency; }
    /** @param value 数字币种 */
    public void setCurrency(Integer value) { currency = value; }
    /** @return 订单状态值 */
    public Integer getState() { return state; }
    /** @param value 订单状态值 */
    public void setState(Integer value) { state = value; }
    /** @return 验证码 */
    public String getSmsCode() { return smsCode; }
    /** @param value 验证码 */
    public void setSmsCode(String value) { smsCode = value; }
    /** @return 收码时间 */
    public Long getReceivedAt() { return receivedAt; }
    /** @param value 收码时间 */
    public void setReceivedAt(Long value) { receivedAt = value; }
    /** @return 最早可取消时间 */
    public Long getCancelAfter() { return cancelAfter; }
    /** @param value 最早可取消时间 */
    public void setCancelAfter(Long value) { cancelAfter = value; }
    /** @return 号码到期时间 */
    public Long getExpiresAt() { return expiresAt; }
    /** @param value 号码到期时间 */
    public void setExpiresAt(Long value) { expiresAt = value; }
    /** @return 操作人 */
    public Long getCreatedBy() { return createdBy; }
    /** @param value 操作人 */
    public void setCreatedBy(Long value) { createdBy = value; }
    /** @return 创建时间 */
    public Long getCreatedAt() { return createdAt; }
    /** @param value 创建时间 */
    public void setCreatedAt(Long value) { createdAt = value; }
    /** @return 变更时间 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param value 变更时间 */
    public void setUpdatedAt(Long value) { updatedAt = value; }

    /** 号码与验证码不进入默认字符串输出。 */
    @Override
    public String toString() {
        return "SmsActivation[id=" + id + ", activationId=" + activationId + ", state=" + state + "]";
    }
}
