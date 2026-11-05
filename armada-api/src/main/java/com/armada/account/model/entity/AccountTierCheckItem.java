package com.armada.account.model.entity;

/** 批次内单账号检测快照，重试失败项不会覆盖历史成功快照。 */
public class AccountTierCheckItem {
    /** 快照主键 */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 检测批次 */
    private Long taskId;
    /** 目标账号 */
    private Long accountId;
    /** 目标号码快照，禁止日志输出 */
    private String phone;
    /** 目标协议/凭据版本摘要，防止串身份 */
    private String targetIdentity;
    /** 实际发起账号 */
    private Long executorAccountId;
    /** 发起账号本轮累计查询数，跨批次承接 */
    private Integer executorRoundCount;
    /** 1待查2查询中3成功4失败5不可查6取消 */
    private Integer status;
    /** 固定原因码 */
    private String reasonCode;
    /** 服务器类型 */
    private String serverUserType;
    /** 服务器档位；无数据为空 */
    private String trustTier;
    /** 服务器可疑标志 */
    private Boolean isSuspicious;
    /** 服务器封禁标志 */
    private Boolean isBanned;
    /** 服务器认证标志 */
    private Boolean isMvSubscribed;
    /** 服务器注册时间 */
    private Long joinDateMs;
    /** 服务器国家 */
    private String phoneCountryCode;
    /** 前次成功快照 */
    private Long prevItemId;
    /** 前次成功档位 */
    private String prevTrustTier;
    /** 前次成功时间 */
    private Long prevCheckedAt;
    /** 0首次1不变2升3降4不明5未查到 */
    private Integer tierChange;
    /** 检测完成毫秒 */
    private Long checkedAt;
    /** 最近实际派发毫秒，重试排队不改写。 */
    private Long lastAttemptAt;
    public Long getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Long value) { lastAttemptAt = value; }
    /** 尝试次数兼作回执隔离令牌 */
    private Integer attemptNo;
    /** 到期认领/账号冷却水位，查询中为租约截止 */
    private Long nextExecuteAt;
    /** 创建毫秒 */
    private Long createdAt;
    /** 更新毫秒 */
    private Long updatedAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { this.taskId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { this.accountId = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { this.phone = value; }
    public String getTargetIdentity() { return targetIdentity; }
    public void setTargetIdentity(String value) { this.targetIdentity = value; }
    public Long getExecutorAccountId() { return executorAccountId; }
    public void setExecutorAccountId(Long value) { this.executorAccountId = value; }
    public Integer getExecutorRoundCount() { return executorRoundCount; }
    public void setExecutorRoundCount(Integer value) { this.executorRoundCount = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { this.status = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { this.reasonCode = value; }
    public String getServerUserType() { return serverUserType; }
    public void setServerUserType(String value) { this.serverUserType = value; }
    public String getTrustTier() { return trustTier; }
    public void setTrustTier(String value) { this.trustTier = value; }
    public Boolean getIsSuspicious() { return isSuspicious; }
    public void setIsSuspicious(Boolean value) { this.isSuspicious = value; }
    public Boolean getIsBanned() { return isBanned; }
    public void setIsBanned(Boolean value) { this.isBanned = value; }
    public Boolean getIsMvSubscribed() { return isMvSubscribed; }
    public void setIsMvSubscribed(Boolean value) { this.isMvSubscribed = value; }
    public Long getJoinDateMs() { return joinDateMs; }
    public void setJoinDateMs(Long value) { this.joinDateMs = value; }
    public String getPhoneCountryCode() { return phoneCountryCode; }
    public void setPhoneCountryCode(String value) { this.phoneCountryCode = value; }
    public Long getPrevItemId() { return prevItemId; }
    public void setPrevItemId(Long value) { this.prevItemId = value; }
    public String getPrevTrustTier() { return prevTrustTier; }
    public void setPrevTrustTier(String value) { this.prevTrustTier = value; }
    public Long getPrevCheckedAt() { return prevCheckedAt; }
    public void setPrevCheckedAt(Long value) { this.prevCheckedAt = value; }
    public Integer getTierChange() { return tierChange; }
    public void setTierChange(Integer value) { this.tierChange = value; }
    public Long getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Long value) { this.checkedAt = value; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer value) { this.attemptNo = value; }
    public Long getNextExecuteAt() { return nextExecuteAt; }
    public void setNextExecuteAt(Long value) { this.nextExecuteAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
