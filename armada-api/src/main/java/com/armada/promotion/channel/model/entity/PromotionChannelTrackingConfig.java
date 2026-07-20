package com.armada.promotion.channel.model.entity;

/** 渠道 Pixel/CAPI 敏感配置写入实体，Access Token 只承载密文。 */
public class PromotionChannelTrackingConfig {

    /** 追踪配置主键。 */
    private Long id;
    /** 所属渠道 ID。 */
    private Long channelId;
    /** 追踪平台代码，与渠道推广平台保持一致。 */
    private Integer providerType;
    /** Pixel 或其他平台追踪标识。 */
    private String trackingId;
    /** AES-GCM 加密后的 Token 字节，不含明文。 */
    private byte[] accessTokenCiphertext;
    /** Token 加密密钥版本。 */
    private String encryptionKeyId;
    /** Token 的 SHA-256 不可逆指纹。 */
    private byte[] tokenFingerprint;
    /** 意向用户上报事件名。 */
    private String leadEventName;
    /** 请求登录上报事件名。 */
    private String loginRequestEventName;
    /** 登录成功上报事件名。 */
    private String loginSuccessEventName;
    /** 创建人用户 ID。 */
    private Long createdBy;
    /** 最近修改人用户 ID。 */
    private Long updatedBy;
    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;
    /** 更新时间，epoch 毫秒。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    public Integer getProviderType() {
        return providerType;
    }

    public void setProviderType(Integer providerType) {
        this.providerType = providerType;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
    }

    public byte[] getAccessTokenCiphertext() {
        return accessTokenCiphertext;
    }

    public void setAccessTokenCiphertext(byte[] accessTokenCiphertext) {
        this.accessTokenCiphertext = accessTokenCiphertext;
    }

    public String getEncryptionKeyId() {
        return encryptionKeyId;
    }

    public void setEncryptionKeyId(String encryptionKeyId) {
        this.encryptionKeyId = encryptionKeyId;
    }

    public byte[] getTokenFingerprint() {
        return tokenFingerprint;
    }

    public void setTokenFingerprint(byte[] tokenFingerprint) {
        this.tokenFingerprint = tokenFingerprint;
    }

    public String getLeadEventName() {
        return leadEventName;
    }

    public void setLeadEventName(String leadEventName) {
        this.leadEventName = leadEventName;
    }

    public String getLoginRequestEventName() {
        return loginRequestEventName;
    }

    public void setLoginRequestEventName(String loginRequestEventName) {
        this.loginRequestEventName = loginRequestEventName;
    }

    public String getLoginSuccessEventName() {
        return loginSuccessEventName;
    }

    public void setLoginSuccessEventName(String loginSuccessEventName) {
        this.loginSuccessEventName = loginSuccessEventName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
