package com.armada.promotion.channel.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 推广渠道编辑入参。渠道 ID 来自路径，租户由请求上下文提供。
 *
 * @param channelName 渠道名称
 * @param ownerUserId 归属用户 ID，同时作为当前阶段的修改人
 * @param targetCountry 国家下拉 value；真实国家为 ISO2，混合国家为 MIXED
 * @param landingTemplateId 绑定的落地页模板 ID
 * @param domain 访问域名，可传纯域名或 https:// 前缀
 * @param themeColor 落地页主题色；未传时兼容旧调用并保留数据库原值
 * @param showAppDownload 是否展示落地页底部应用下载区域；未传时保留数据库原值
 * @param preselectedCountry 落地页手机号输入框默认区号国家 ISO2，不允许 MIXED
 * @param platform 推广平台：1=Facebook、2=TikTok、3=快手、4=MGSKY Ads
 * @param trackingId Pixel 或其他平台追踪 ID
 * @param accessToken 新 CAPI Access Token；平台和追踪 ID 均未变且已有完整密文时，空白表示保留原值
 * @param leadEventName 意向用户上报事件
 * @param loginRequestEventName 请求登录上报事件
 * @param loginSuccessEventName 登录成功上报事件
 * @param inAppOpenAllowed 是否允许在推广平台内置浏览器打开
 * @param marketingAllowed 是否允许参加营销活动
 * @param status 渠道状态：0=禁用、1=启用
 */
public record PromotionChannelUpdateDTO(
        String channelName,
        Long ownerUserId,
        String targetCountry,
        Long landingTemplateId,
        String domain,
        String themeColor,
        Boolean showAppDownload,
        String preselectedCountry,
        Integer platform,
        @JsonAlias({"fbPixelId", "tiktokPixelId"}) String trackingId,
        @JsonAlias({"fbAccessToken", "tiktokAccessToken"}) String accessToken,
        String leadEventName,
        String loginRequestEventName,
        String loginSuccessEventName,
        Boolean inAppOpenAllowed,
        Boolean marketingAllowed,
        Integer status) {
}
