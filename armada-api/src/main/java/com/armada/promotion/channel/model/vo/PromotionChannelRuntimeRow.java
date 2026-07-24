package com.armada.promotion.channel.model.vo;

/** 公共落地页运行时查询投影，只包含渲染页面所需的非敏感配置。 */
public class PromotionChannelRuntimeRow {

    private String templateCode;
    private String themeColor;
    private Integer isAppDownloadShown;
    private String targetCountry;
    private String preselectedCountry;

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
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
}
