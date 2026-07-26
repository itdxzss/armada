package com.armada.promotion.channel.model.vo;

/**
 * 公开落地页运行时配置。
 *
 * <p>该模型刻意排除渠道归属、管理状态和广告平台凭据，避免公开接口泄露管理端数据。</p>
 */
public record PromotionChannelRuntimeVO(
        String templateCode,
        String themeColor,
        boolean showAppDownload,
        String targetCountry,
        String preselectedCountry) {
}
