package com.armada.promotion.channel.model.vo;

/**
 * 渠道探测专用数据库投影。
 *
 * <p>该类型包含 Token 密文，只允许在 Service 内部使用，禁止作为 Controller 响应。</p>
 */
public class PromotionChannelProbeConfigRow {

    /** 渠道 ID。 */
    private Long channelId;
    /** 渠道归属用户，用作追踪配置更新人。 */
    private Long ownerUserId;
    /** 推广平台代码。 */
    private Integer platform;
    /** 渠道公开码。 */
    private String channelCode;
    /** 渠道访问域名。 */
    private String domainHost;
    /** 追踪配置 ID；为空表示尚未配置。 */
    private Long trackingConfigId;
    /** Pixel 或平台追踪 ID。 */
    private String trackingId;
    /** AES-GCM Token 密文。 */
    private byte[] accessTokenCiphertext;
    /** Token 密钥版本。 */
    private String encryptionKeyId;
    /** Token 不可逆指纹，用于判断配置完整性。 */
    private byte[] tokenFingerprint;

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Integer getPlatform() { return platform; }
    public void setPlatform(Integer platform) { this.platform = platform; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getDomainHost() { return domainHost; }
    public void setDomainHost(String domainHost) { this.domainHost = domainHost; }
    public Long getTrackingConfigId() { return trackingConfigId; }
    public void setTrackingConfigId(Long trackingConfigId) { this.trackingConfigId = trackingConfigId; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public byte[] getAccessTokenCiphertext() { return accessTokenCiphertext; }
    public void setAccessTokenCiphertext(byte[] accessTokenCiphertext) {
        this.accessTokenCiphertext = accessTokenCiphertext;
    }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }
    public byte[] getTokenFingerprint() { return tokenFingerprint; }
    public void setTokenFingerprint(byte[] tokenFingerprint) { this.tokenFingerprint = tokenFingerprint; }
}
