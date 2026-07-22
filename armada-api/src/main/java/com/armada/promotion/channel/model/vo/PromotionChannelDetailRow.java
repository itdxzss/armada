package com.armada.promotion.channel.model.vo;

/** MyBatis 渠道编辑详情投影；敏感 Token 材料只在 SQL 内判断完整性，不进入该对象。 */
public class PromotionChannelDetailRow {

    private Long id;
    private String channelName;
    private Long ownerUserId;
    private String targetCountry;
    private Long landingTemplateId;
    private String domain;
    private String themeColor;
    private Integer isAppDownloadShown;
    private String preselectedCountry;
    private Integer platform;
    private String trackingId;
    private Integer accessTokenConfigured;
    private String leadEventName;
    private String loginRequestEventName;
    private String loginSuccessEventName;
    private Integer isInAppOpenAllowed;
    private Integer isMarketingAllowed;
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTargetCountry() { return targetCountry; }
    public void setTargetCountry(String targetCountry) { this.targetCountry = targetCountry; }
    public Long getLandingTemplateId() { return landingTemplateId; }
    public void setLandingTemplateId(Long landingTemplateId) { this.landingTemplateId = landingTemplateId; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }
    public Integer getIsAppDownloadShown() { return isAppDownloadShown; }
    public void setIsAppDownloadShown(Integer isAppDownloadShown) {
        this.isAppDownloadShown = isAppDownloadShown;
    }
    public String getPreselectedCountry() { return preselectedCountry; }
    public void setPreselectedCountry(String preselectedCountry) { this.preselectedCountry = preselectedCountry; }
    public Integer getPlatform() { return platform; }
    public void setPlatform(Integer platform) { this.platform = platform; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public Integer getAccessTokenConfigured() { return accessTokenConfigured; }
    public void setAccessTokenConfigured(Integer accessTokenConfigured) {
        this.accessTokenConfigured = accessTokenConfigured;
    }
    public String getLeadEventName() { return leadEventName; }
    public void setLeadEventName(String leadEventName) { this.leadEventName = leadEventName; }
    public String getLoginRequestEventName() { return loginRequestEventName; }
    public void setLoginRequestEventName(String loginRequestEventName) {
        this.loginRequestEventName = loginRequestEventName;
    }
    public String getLoginSuccessEventName() { return loginSuccessEventName; }
    public void setLoginSuccessEventName(String loginSuccessEventName) {
        this.loginSuccessEventName = loginSuccessEventName;
    }
    public Integer getIsInAppOpenAllowed() { return isInAppOpenAllowed; }
    public void setIsInAppOpenAllowed(Integer isInAppOpenAllowed) {
        this.isInAppOpenAllowed = isInAppOpenAllowed;
    }
    public Integer getIsMarketingAllowed() { return isMarketingAllowed; }
    public void setIsMarketingAllowed(Integer isMarketingAllowed) {
        this.isMarketingAllowed = isMarketingAllowed;
    }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
