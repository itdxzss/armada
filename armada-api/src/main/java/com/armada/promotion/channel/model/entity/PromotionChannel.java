package com.armada.promotion.channel.model.entity;

/** 推广渠道主表写入实体。 */
public class PromotionChannel {

    /** 渠道主键。 */
    private Long id;
    /** 对外公开且租户内唯一的渠道短码。 */
    private String channelCode;
    /** 运营配置的渠道名称。 */
    private String channelName;
    /** 渠道归属用户 ID，同时作为当前阶段创建人筛选值。 */
    private Long ownerUserId;
    /** 域名绑定记录 ID，通过它间接关联落地页模板。 */
    private Long promotionDomainId;
    /** 落地页主题色，统一保存为小写六位十六进制颜色。 */
    private String themeColor;
    /** 是否展示落地页底部应用下载区域：0=否、1=是。 */
    private Integer isAppDownloadShown;
    /** 目标国家下拉 value：真实国家为 ISO2，混合国家为 MIXED。 */
    private String targetCountry;
    /** 落地页手机号输入框默认区号国家 ISO2，不允许 MIXED。 */
    private String preselectedCountry;
    /** 推广平台代码：1=Facebook、2=TikTok、3=快手、4=MGSKY Ads。 */
    private Integer platform;
    /** 是否允许在推广平台内置浏览器打开：0=否、1=是。 */
    private Integer isInAppOpenAllowed;
    /** 是否允许渠道参加营销：0=否、1=是。 */
    private Integer isMarketingAllowed;
    /** 渠道状态：0=停用、1=启用。 */
    private Integer status;
    /** 创建人用户 ID；新增时等于 ownerUserId。 */
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

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getPromotionDomainId() {
        return promotionDomainId;
    }

    public void setPromotionDomainId(Long promotionDomainId) {
        this.promotionDomainId = promotionDomainId;
    }

    public String getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(String themeColor) {
        this.themeColor = themeColor;
    }

    public Integer getIsAppDownloadShown() {
        return isAppDownloadShown;
    }

    public void setIsAppDownloadShown(Integer isAppDownloadShown) {
        this.isAppDownloadShown = isAppDownloadShown;
    }

    public String getTargetCountry() {
        return targetCountry;
    }

    public void setTargetCountry(String targetCountry) {
        this.targetCountry = targetCountry;
    }

    public String getPreselectedCountry() {
        return preselectedCountry;
    }

    public void setPreselectedCountry(String preselectedCountry) {
        this.preselectedCountry = preselectedCountry;
    }

    public Integer getPlatform() {
        return platform;
    }

    public void setPlatform(Integer platform) {
        this.platform = platform;
    }

    public Integer getIsInAppOpenAllowed() {
        return isInAppOpenAllowed;
    }

    public void setIsInAppOpenAllowed(Integer isInAppOpenAllowed) {
        this.isInAppOpenAllowed = isInAppOpenAllowed;
    }

    public Integer getIsMarketingAllowed() {
        return isMarketingAllowed;
    }

    public void setIsMarketingAllowed(Integer isMarketingAllowed) {
        this.isMarketingAllowed = isMarketingAllowed;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
