package com.armada.promotion.pairing.model.entity;

/** 推广落地页 WhatsApp 配对会话实体。 */
public class PromotionPairingSession {
    /** 配对会话主键。 */
    private Long id;
    /** 渠道所属租户 ID。 */
    private Long tenantId;
    /** 发起配对的推广渠道 ID。 */
    private Long promotionChannelId;
    /** 发起配对时的渠道名称快照。 */
    private String channelName;
    /** 渠道归属用户 ID 快照。 */
    private Long ownerUserId;
    /** 公开会话令牌的 SHA-256 十六进制摘要。 */
    private String sessionTokenHash;
    /** 只包含数字的完整国际手机号。 */
    private String phone;
    /** 协议层使用的账号句柄。 */
    private String protocolAccountId;
    /** 协议层为本次请求生成的配对任务 ID。 */
    private String pairingId;
    /** 协议层随机生成并等待手机确认的配对码。 */
    private String pairingCode;
    /** 配对状态，取值见 PromotionPairingStatus。 */
    private Integer status;
    /** 本次会话临时预留的代理 ID。 */
    private Long proxyId;
    /** 协议层 sticky 代理会话 ID。 */
    private String proxySessionId;
    /** 实际分配代理的国家或区域快照。 */
    private String proxyRegion;
    /** 实际分配代理的来源快照。 */
    private String proxySource;
    /** 配对成功后创建的正式账号 ID。 */
    private Long accountId;
    /** 配对码到期时间，单位为 epoch 毫秒。 */
    private Long expiresAt;
    /** 可公开返回的脱敏失败码。 */
    private String errorCode;
    /** 可公开返回的脱敏失败摘要。 */
    private String errorMessage;
    /** 会话进入终态的时间，单位为 epoch 毫秒。 */
    private Long completedAt;
    /** 会话创建时间，单位为 epoch 毫秒。 */
    private Long createdAt;
    /** 会话最后更新时间，单位为 epoch 毫秒。 */
    private Long updatedAt;

    /** @return 配对会话主键 */
    public Long getId() { return id; }
    /** @param id 配对会话主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 渠道所属租户 ID */
    public Long getTenantId() { return tenantId; }
    /** @param tenantId 渠道所属租户 ID */
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    /** @return 发起配对的推广渠道 ID */
    public Long getPromotionChannelId() { return promotionChannelId; }
    /** @param promotionChannelId 发起配对的推广渠道 ID */
    public void setPromotionChannelId(Long promotionChannelId) { this.promotionChannelId = promotionChannelId; }
    /** @return 发起配对时的渠道名称快照 */
    public String getChannelName() { return channelName; }
    /** @param channelName 发起配对时的渠道名称快照 */
    public void setChannelName(String channelName) { this.channelName = channelName; }
    /** @return 渠道归属用户 ID 快照 */
    public Long getOwnerUserId() { return ownerUserId; }
    /** @param ownerUserId 渠道归属用户 ID 快照 */
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    /** @return 会话令牌摘要 */
    public String getSessionTokenHash() { return sessionTokenHash; }
    /** @param sessionTokenHash 会话令牌摘要 */
    public void setSessionTokenHash(String sessionTokenHash) { this.sessionTokenHash = sessionTokenHash; }
    /** @return 完整国际手机号 */
    public String getPhone() { return phone; }
    /** @param phone 完整国际手机号 */
    public void setPhone(String phone) { this.phone = phone; }
    /** @return 协议层账号句柄 */
    public String getProtocolAccountId() { return protocolAccountId; }
    /** @param protocolAccountId 协议层账号句柄 */
    public void setProtocolAccountId(String protocolAccountId) { this.protocolAccountId = protocolAccountId; }
    /** @return 协议层配对任务 ID */
    public String getPairingId() { return pairingId; }
    /** @param pairingId 协议层配对任务 ID */
    public void setPairingId(String pairingId) { this.pairingId = pairingId; }
    /** @return 等待手机确认的随机配对码 */
    public String getPairingCode() { return pairingCode; }
    /** @param pairingCode 等待手机确认的随机配对码 */
    public void setPairingCode(String pairingCode) { this.pairingCode = pairingCode; }
    /** @return 配对状态数据库码 */
    public Integer getStatus() { return status; }
    /** @param status 配对状态数据库码 */
    public void setStatus(Integer status) { this.status = status; }
    /** @return 本次会话临时预留的代理 ID */
    public Long getProxyId() { return proxyId; }
    /** @param proxyId 本次会话临时预留的代理 ID */
    public void setProxyId(Long proxyId) { this.proxyId = proxyId; }
    /** @return 协议层 sticky 代理会话 ID */
    public String getProxySessionId() { return proxySessionId; }
    /** @param proxySessionId 协议层 sticky 代理会话 ID */
    public void setProxySessionId(String proxySessionId) { this.proxySessionId = proxySessionId; }
    /** @return 实际分配代理的国家或区域快照 */
    public String getProxyRegion() { return proxyRegion; }
    /** @param proxyRegion 实际分配代理的国家或区域快照 */
    public void setProxyRegion(String proxyRegion) { this.proxyRegion = proxyRegion; }
    /** @return 实际分配代理的来源快照 */
    public String getProxySource() { return proxySource; }
    /** @param proxySource 实际分配代理的来源快照 */
    public void setProxySource(String proxySource) { this.proxySource = proxySource; }
    /** @return 配对成功后创建的正式账号 ID */
    public Long getAccountId() { return accountId; }
    /** @param accountId 配对成功后创建的正式账号 ID */
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    /** @return 配对码到期时间，epoch 毫秒 */
    public Long getExpiresAt() { return expiresAt; }
    /** @param expiresAt 配对码到期时间，epoch 毫秒 */
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    /** @return 脱敏失败码 */
    public String getErrorCode() { return errorCode; }
    /** @param errorCode 脱敏失败码 */
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    /** @return 脱敏失败摘要 */
    public String getErrorMessage() { return errorMessage; }
    /** @param errorMessage 脱敏失败摘要 */
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    /** @return 会话进入终态的时间，epoch 毫秒 */
    public Long getCompletedAt() { return completedAt; }
    /** @param completedAt 会话进入终态的时间，epoch 毫秒 */
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    /** @return 会话创建时间，epoch 毫秒 */
    public Long getCreatedAt() { return createdAt; }
    /** @param createdAt 会话创建时间，epoch 毫秒 */
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    /** @return 会话最后更新时间，epoch 毫秒 */
    public Long getUpdatedAt() { return updatedAt; }
    /** @param updatedAt 会话最后更新时间，epoch 毫秒 */
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
