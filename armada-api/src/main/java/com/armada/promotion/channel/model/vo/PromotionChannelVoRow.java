package com.armada.promotion.channel.model.vo;

/** MyBatis 渠道分页投影，不包含 Access Token 密文与指纹。 */
public class PromotionChannelVoRow {

    /** 渠道主键。 */
    private Long id;
    /** 渠道名称。 */
    private String channelName;
    /** 公开推广码。 */
    private String channelCode;
    /** 归属用户 ID，也是当前页面创建人筛选值。 */
    private Long ownerUserId;
    /** 目标国家下拉 value：真实国家为 ISO2，混合国家为 MIXED。 */
    private String targetCountry;
    /** 预选区号国家 ISO2。 */
    private String preselectedCountry;
    /** 绑定模板 ID。 */
    private Long landingTemplateId;
    /** 绑定模板名称。 */
    private String templateName;
    /** 已规范化的域名主机名。 */
    private String domainHost;
    /** 推广平台代码。 */
    private Integer platform;
    /** Pixel 或平台追踪 ID；不包含 Token。 */
    private String trackingId;
    /** 最近一次 CAPI 探测状态。 */
    private Integer lastProbeStatus;
    /** 渠道启停状态。 */
    private Integer status;
    /** 是否允许应用内打开。 */
    private Integer isInAppOpenAllowed;
    /** 是否允许参加营销。 */
    private Integer isMarketingAllowed;
    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTargetCountry() { return targetCountry; }
    public void setTargetCountry(String targetCountry) { this.targetCountry = targetCountry; }
    public String getPreselectedCountry() { return preselectedCountry; }
    public void setPreselectedCountry(String preselectedCountry) { this.preselectedCountry = preselectedCountry; }
    public Long getLandingTemplateId() { return landingTemplateId; }
    public void setLandingTemplateId(Long landingTemplateId) { this.landingTemplateId = landingTemplateId; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getDomainHost() { return domainHost; }
    public void setDomainHost(String domainHost) { this.domainHost = domainHost; }
    public Integer getPlatform() { return platform; }
    public void setPlatform(Integer platform) { this.platform = platform; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public Integer getLastProbeStatus() { return lastProbeStatus; }
    public void setLastProbeStatus(Integer lastProbeStatus) { this.lastProbeStatus = lastProbeStatus; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getIsInAppOpenAllowed() { return isInAppOpenAllowed; }
    public void setIsInAppOpenAllowed(Integer isInAppOpenAllowed) { this.isInAppOpenAllowed = isInAppOpenAllowed; }
    public Integer getIsMarketingAllowed() { return isMarketingAllowed; }
    public void setIsMarketingAllowed(Integer isMarketingAllowed) { this.isMarketingAllowed = isMarketingAllowed; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
