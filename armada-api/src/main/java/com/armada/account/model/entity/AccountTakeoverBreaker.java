package com.armada.account.model.entity;

/** 每个租户账号的自动抢登固定窗口与持久熔断事实。 */
public class AccountTakeoverBreaker {

    /** 熔断记录主键。 */
    private Long id;
    /** 所属租户。 */
    private Long tenantId;
    /** Armada 账号主键。 */
    private Long accountId;
    /** 固定窗口内首次被挤的时间，毫秒。 */
    private Long windowStartedAt;
    /** 当前窗口内累计被挤次数。 */
    private Integer kickCount;
    /** 熔断触发时间，非空时必须人工清零才能恢复。 */
    private Long trippedAt;
    /** 记录创建时间，毫秒。 */
    private Long createdAt;
    /** 记录最后写入时间，毫秒。 */
    private Long updatedAt;

    /** @return 熔断记录主键 */
    public Long getId() { return id; }
    /** @param id 熔断记录主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属租户 */
    public Long getTenantId() { return tenantId; }
    /** @param tenantId 所属租户 */
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    /** @return Armada 账号主键 */
    public Long getAccountId() { return accountId; }
    /** @param accountId Armada 账号主键 */
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    /** @return 固定窗口起点，毫秒 */
    public Long getWindowStartedAt() { return windowStartedAt; }
    /** @param windowStartedAt 固定窗口起点，毫秒 */
    public void setWindowStartedAt(Long windowStartedAt) { this.windowStartedAt = windowStartedAt; }
    /** @return 当前窗口累计被挤次数 */
    public Integer getKickCount() { return kickCount; }
    /** @param kickCount 当前窗口累计被挤次数 */
    public void setKickCount(Integer kickCount) { this.kickCount = kickCount; }
    /** @return 熔断时间；空表示未熔断 */
    public Long getTrippedAt() { return trippedAt; }
    /** @param trippedAt 熔断时间；空表示未熔断 */
    public void setTrippedAt(Long trippedAt) { this.trippedAt = trippedAt; }
    /** @return 记录创建时间，毫秒 */
    public Long getCreatedAt() { return createdAt; }
    /** @param createdAt 记录创建时间，毫秒 */
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    /** @return 记录最后写入时间，毫秒 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param updatedAt 记录最后写入时间，毫秒 */
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
